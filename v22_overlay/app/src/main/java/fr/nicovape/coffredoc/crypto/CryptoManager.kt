package fr.nicovape.coffredoc.crypto

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

/** Pont entre le Storage Access Framework Android et le moteur AES-GCM natif. */
class CryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val operationLock = Any()
    private val keyProvider = AndroidKeystoreKeyProvider()
    private val codec = ChunkedAesGcm()

    /** Crée la clé V3 authentifiée avant l'affichage du dialogue biométrique. */
    fun prepareAuthenticatedKey() {
        keyProvider.getOrCreateAuthenticatedKey()
    }

    /** Chiffre en V3 avec la clé dont l'usage est protégé par l'authentification. */
    fun encrypt(sourceUri: Uri, destinationUri: Uri): Long = synchronized(operationLock) {
        ensureDifferentDocuments(sourceUri, destinationUri)
        try {
            val key = keyProvider.getOrCreateAuthenticatedKey()
            val resolver = appContext.contentResolver

            resolver.openInputStream(sourceUri)
                ?.let(::BufferedInputStream)
                ?.use { clearInput ->
                    resolver.openOutputStream(destinationUri, "w")
                        ?.let(::BufferedOutputStream)
                        ?.use { encryptedOutput ->
                            codec.encrypt(clearInput, encryptedOutput, key)
                        }
                        ?: throw IOException("Impossible de créer le document chiffré.")
                }
                ?: throw IOException("Impossible d'ouvrir le document source.")
        } catch (error: Throwable) {
            removeOrTruncateDestination(destinationUri)
            throw error
        }
    }

    /**
     * Déchiffre les conteneurs V3 et conserve la lecture des anciens conteneurs V2
     * quand leur ancienne clé existe encore dans cette installation Android.
     */
    fun decrypt(sourceUri: Uri, destinationUri: Uri): Long = synchronized(operationLock) {
        ensureDifferentDocuments(sourceUri, destinationUri)
        val localEncryptedCopy = createEncryptedTemporaryFile()

        try {
            copyEncryptedSourceToTemporaryFile(sourceUri, localEncryptedCopy)
            val formatVersion = localEncryptedCopy.inputStream()
                .let(::BufferedInputStream)
                .use(codec::detectFormatVersion)

            val key = when (formatVersion) {
                ChunkedAesGcm.FORMAT_VERSION_AUTHENTICATED ->
                    keyProvider.getOrCreateAuthenticatedKey()

                ChunkedAesGcm.FORMAT_VERSION_LEGACY_NATIVE ->
                    keyProvider.getLegacyV2KeyOrNull()
                        ?: throw CoffreDocLegacyKeyUnavailableException(
                            "L'ancienne clé V2 n'existe plus sur ce téléphone."
                        )

                else -> throw CoffreDocFormatException(
                    "Version NicoVault non prise en charge : $formatVersion."
                )
            }

            // Première passe : vérification intégrale sans produire de texte clair.
            localEncryptedCopy.inputStream()
                .let(::BufferedInputStream)
                .use { encryptedInput ->
                    codec.decrypt(encryptedInput, output = null, key = key)
                }

            // Deuxième passe : restitution uniquement après validation complète.
            val resolver = appContext.contentResolver
            resolver.openOutputStream(destinationUri, "w")
                ?.let(::BufferedOutputStream)
                ?.use { clearOutput ->
                    localEncryptedCopy.inputStream()
                        .let(::BufferedInputStream)
                        .use { encryptedInput ->
                            codec.decrypt(encryptedInput, clearOutput, key)
                        }
                }
                ?: throw IOException("Impossible de créer le document déchiffré.")
        } catch (error: Throwable) {
            removeOrTruncateDestination(destinationUri)
            throw error
        } finally {
            localEncryptedCopy.delete()
        }
    }

    fun cleanupTemporaryFiles() = synchronized(operationLock) {
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith(TEMP_FILE_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun ensureDifferentDocuments(sourceUri: Uri, destinationUri: Uri) {
        if (sourceUri == destinationUri) {
            throw IOException(
                "Le document source et le document de destination doivent être différents."
            )
        }
    }

    private fun copyEncryptedSourceToTemporaryFile(sourceUri: Uri, destination: File) {
        appContext.contentResolver.openInputStream(sourceUri)
            ?.let(::BufferedInputStream)
            ?.use { sourceInput ->
                destination.outputStream()
                    .let(::BufferedOutputStream)
                    .use { temporaryOutput ->
                        sourceInput.copyTo(temporaryOutput, BUFFER_SIZE)
                    }
            }
            ?: throw IOException("Impossible d'ouvrir le fichier chiffré.")

        if (destination.length() == 0L) {
            throw CoffreDocFormatException("Le fichier chiffré est vide.")
        }
    }

    private fun createEncryptedTemporaryFile(): File = File.createTempFile(
        TEMP_FILE_PREFIX,
        TEMP_FILE_SUFFIX,
        appContext.cacheDir
    )

    private fun removeOrTruncateDestination(uri: Uri) {
        val resolver = appContext.contentResolver
        val deleted = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)

        if (!deleted) {
            runCatching {
                resolver.openOutputStream(uri, "rwt")?.use { it.flush() }
            }.recoverCatching {
                resolver.openOutputStream(uri, "w")?.use { it.flush() }
            }
        }
    }

    private companion object {
        const val TEMP_FILE_PREFIX = "nicovault_v3_"
        const val TEMP_FILE_SUFFIX = ".ncrypt.tmp"
        const val BUFFER_SIZE = 64 * 1024
    }
}
