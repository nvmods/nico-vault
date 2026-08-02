package fr.nicovape.coffredoc.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Format NicoVault V2/V3 par blocs AES-256-GCM authentifiés. */
internal class ChunkedAesGcm {

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        formatVersion: Int = FORMAT_VERSION_AUTHENTICATED
    ): Long {
        val globalHeader = buildGlobalHeader(formatVersion)
        output.write(globalHeader)

        val clearBuffer = ByteArray(CHUNK_SIZE)
        var clearByteCount = 0L
        var chunkIndex = 0

        while (true) {
            val clearLength = readAtMost(input, clearBuffer)
            if (clearLength == 0) break

            writeRecord(
                output,
                key,
                globalHeader,
                RECORD_TYPE_DATA,
                chunkIndex,
                clearBuffer,
                clearLength
            )
            clearByteCount += clearLength
            chunkIndex = nextChunkIndex(chunkIndex)
        }

        // Marque de fin authentifiée pour détecter toute troncature.
        writeRecord(
            output,
            key,
            globalHeader,
            RECORD_TYPE_FINAL,
            chunkIndex,
            EMPTY_BYTES,
            0
        )
        output.flush()
        return clearByteCount
    }

    fun decrypt(input: InputStream, output: OutputStream?, key: SecretKey): Long {
        val parsedHeader = readGlobalHeader(input)
        var expectedChunkIndex = 0
        var clearByteCount = 0L

        while (true) {
            val record = readRecordHeader(
                input,
                expectedChunkIndex,
                parsedHeader.declaredChunkSize
            )
            val encryptedChunk = readExactly(input, safeEncryptedLength(record.clearLength))

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BITS, record.iv)
            )
            cipher.updateAAD(parsedHeader.authenticatedBytes)
            cipher.updateAAD(record.authenticatedBytes)
            val clearChunk = cipher.doFinal(encryptedChunk)

            when (record.type) {
                RECORD_TYPE_DATA -> {
                    if (clearChunk.size != record.clearLength) {
                        throw CoffreDocFormatException(
                            "Longueur de bloc déchiffré incohérente."
                        )
                    }
                    output?.write(clearChunk)
                    clearByteCount += clearChunk.size
                    expectedChunkIndex = nextChunkIndex(expectedChunkIndex)
                }

                RECORD_TYPE_FINAL -> {
                    if (record.clearLength != 0 || clearChunk.isNotEmpty()) {
                        throw CoffreDocFormatException("Marque de fin NicoVault invalide.")
                    }
                    if (input.read() != -1) {
                        throw CoffreDocFormatException(
                            "Données inattendues après la fin du conteneur."
                        )
                    }
                    output?.flush()
                    return clearByteCount
                }

                else -> throw CoffreDocFormatException(
                    "Type de bloc NicoVault inconnu : ${record.type}."
                )
            }
        }
    }

    fun detectFormatVersion(input: InputStream): Int {
        val prefix = readExactly(input, MAGIC.size + 1)
        if (!prefix.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CoffreDocFormatException(
                "Ce fichier n'est pas un conteneur NicoVault valide."
            )
        }

        val version = prefix[MAGIC.size].toInt() and 0xFF
        if (version == LEGACY_ENCRYPTED_FILE_VERSION) {
            throw CoffreDocLegacyFormatException(
                "Ce fichier utilise l'ancien format EncryptedFile de la V1."
            )
        }
        return version
    }

    private fun writeRecord(
        output: OutputStream,
        key: SecretKey,
        globalHeader: ByteArray,
        recordType: Int,
        chunkIndex: Int,
        clearBuffer: ByteArray,
        clearLength: Int
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        if (iv == null || iv.size != IV_LENGTH_BYTES) {
            throw GeneralSecurityException(
                "Le fournisseur AES-GCM n'a pas généré un IV de 96 bits."
            )
        }

        val recordHeader = buildRecordHeader(
            recordType,
            chunkIndex,
            clearLength,
            iv
        )
        cipher.updateAAD(globalHeader)
        cipher.updateAAD(recordHeader)
        val encryptedChunk = cipher.doFinal(clearBuffer, 0, clearLength)

        if (encryptedChunk.size != clearLength + TAG_LENGTH_BYTES) {
            throw GeneralSecurityException("Taille de bloc AES-GCM inattendue.")
        }

        output.write(recordHeader)
        output.write(encryptedChunk)
    }

    private fun readGlobalHeader(input: InputStream): ParsedGlobalHeader {
        val header = readExactly(input, GLOBAL_HEADER_LENGTH)
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CoffreDocFormatException(
                "Ce fichier n'est pas un conteneur NicoVault valide."
            )
        }

        val version = header[MAGIC.size].toInt() and 0xFF
        if (version == LEGACY_ENCRYPTED_FILE_VERSION) {
            throw CoffreDocLegacyFormatException(
                "Ce fichier utilise l'ancien format EncryptedFile de la V1."
            )
        }
        if (version != FORMAT_VERSION_LEGACY_NATIVE &&
            version != FORMAT_VERSION_AUTHENTICATED
        ) {
            throw CoffreDocFormatException(
                "Version NicoVault non prise en charge : $version."
            )
        }

        val algorithm = header[MAGIC.size + 1].toInt() and 0xFF
        if (algorithm != ALGORITHM_AES_256_GCM_CHUNKED) {
            throw CoffreDocFormatException(
                "Algorithme NicoVault non pris en charge : $algorithm."
            )
        }

        val declaredChunkSize = ByteBuffer.wrap(
            header,
            MAGIC.size + 2,
            Int.SIZE_BYTES
        ).order(ByteOrder.BIG_ENDIAN).int

        if (declaredChunkSize !in MIN_ACCEPTED_CHUNK_SIZE..MAX_ACCEPTED_CHUNK_SIZE) {
            throw CoffreDocFormatException(
                "Taille de bloc NicoVault invalide : $declaredChunkSize."
            )
        }

        return ParsedGlobalHeader(header, declaredChunkSize)
    }

    private fun readRecordHeader(
        input: InputStream,
        expectedChunkIndex: Int,
        declaredChunkSize: Int
    ): ParsedRecordHeader {
        val firstByte = input.read()
        if (firstByte < 0) {
            throw CoffreDocFormatException(
                "Conteneur tronqué : marque de fin authentifiée absente."
            )
        }

        val remaining = readExactly(input, RECORD_HEADER_LENGTH - 1)
        val header = ByteArray(RECORD_HEADER_LENGTH)
        header[0] = firstByte.toByte()
        remaining.copyInto(header, destinationOffset = 1)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val type = buffer.get().toInt() and 0xFF
        val chunkIndex = buffer.int
        val clearLength = buffer.int
        val iv = ByteArray(IV_LENGTH_BYTES)
        buffer.get(iv)

        if (chunkIndex != expectedChunkIndex) {
            throw CoffreDocFormatException(
                "Ordre des blocs invalide : attendu $expectedChunkIndex, reçu $chunkIndex."
            )
        }

        when (type) {
            RECORD_TYPE_DATA -> if (clearLength !in 1..declaredChunkSize) {
                throw CoffreDocFormatException(
                    "Longueur de bloc NicoVault invalide : $clearLength."
                )
            }

            RECORD_TYPE_FINAL -> if (clearLength != 0) {
                throw CoffreDocFormatException("La marque de fin doit être vide.")
            }

            else -> throw CoffreDocFormatException(
                "Type de bloc NicoVault inconnu : $type."
            )
        }

        return ParsedRecordHeader(type, clearLength, iv, header)
    }

    private fun buildGlobalHeader(formatVersion: Int): ByteArray {
        if (formatVersion != FORMAT_VERSION_LEGACY_NATIVE &&
            formatVersion != FORMAT_VERSION_AUTHENTICATED
        ) {
            throw CoffreDocFormatException(
                "Version de sortie NicoVault non prise en charge : $formatVersion."
            )
        }

        return ByteBuffer.allocate(GLOBAL_HEADER_LENGTH)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(formatVersion.toByte())
            .put(ALGORITHM_AES_256_GCM_CHUNKED.toByte())
            .putInt(CHUNK_SIZE)
            .array()
    }

    private fun buildRecordHeader(
        type: Int,
        chunkIndex: Int,
        clearLength: Int,
        iv: ByteArray
    ): ByteArray = ByteBuffer.allocate(RECORD_HEADER_LENGTH)
        .order(ByteOrder.BIG_ENDIAN)
        .put(type.toByte())
        .putInt(chunkIndex)
        .putInt(clearLength)
        .put(iv)
        .array()

    private fun readAtMost(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            when {
                read < 0 -> break
                read == 0 -> {
                    val singleByte = input.read()
                    if (singleByte < 0) break
                    buffer[offset++] = singleByte.toByte()
                }
                else -> offset += read
            }
        }
        return offset
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw CoffreDocFormatException("Conteneur NicoVault tronqué.")
            if (read > 0) offset += read
        }
        return result
    }

    private fun safeEncryptedLength(clearLength: Int): Int {
        if (clearLength !in 0..MAX_ACCEPTED_CHUNK_SIZE) {
            throw CoffreDocFormatException("Longueur de bloc hors limites.")
        }
        return clearLength + TAG_LENGTH_BYTES
    }

    private fun nextChunkIndex(current: Int): Int {
        if (current == Int.MAX_VALUE) {
            throw CoffreDocFormatException("Document trop volumineux pour ce format.")
        }
        return current + 1
    }

    private data class ParsedGlobalHeader(
        val authenticatedBytes: ByteArray,
        val declaredChunkSize: Int
    )

    private data class ParsedRecordHeader(
        val type: Int,
        val clearLength: Int,
        val iv: ByteArray,
        val authenticatedBytes: ByteArray
    )

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val MAGIC = byteArrayOf(0x43, 0x44, 0x4F, 0x43)
        const val LEGACY_ENCRYPTED_FILE_VERSION = 1
        const val FORMAT_VERSION_LEGACY_NATIVE = 2
        const val FORMAT_VERSION_AUTHENTICATED = 3
        const val ALGORITHM_AES_256_GCM_CHUNKED = 1
        const val CHUNK_SIZE = 1024 * 1024
        const val MIN_ACCEPTED_CHUNK_SIZE = 4 * 1024
        const val MAX_ACCEPTED_CHUNK_SIZE = 4 * 1024 * 1024
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / Byte.SIZE_BITS
        const val RECORD_TYPE_DATA = 0
        const val RECORD_TYPE_FINAL = 1
        const val GLOBAL_HEADER_LENGTH = 4 + 1 + 1 + Int.SIZE_BYTES
        const val RECORD_HEADER_LENGTH = 1 + Int.SIZE_BYTES + Int.SIZE_BYTES + IV_LENGTH_BYTES
        val EMPTY_BYTES = ByteArray(0)
    }
}

open class CoffreDocFormatException(message: String) : IOException(message)
class CoffreDocLegacyFormatException(message: String) : CoffreDocFormatException(message)
class CoffreDocLegacyKeyUnavailableException(message: String) : CoffreDocFormatException(message)
