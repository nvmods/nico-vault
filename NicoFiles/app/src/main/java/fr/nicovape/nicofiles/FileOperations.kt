package fr.nicovape.nicofiles

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileOperations {
    private const val BUFFER_SIZE = 256 * 1024

    fun copyOrMove(sources: List<File>, destinationDirectory: File, move: Boolean): List<File> {
        require(destinationDirectory.isDirectory) { "La destination n'est pas un dossier." }
        val results = mutableListOf<File>()
        sources.forEach { source ->
            if (!source.exists()) return@forEach
            if (source.isDirectory && isInside(destinationDirectory, source)) {
                throw IOException("Impossible de copier un dossier à l'intérieur de lui-même : ${source.name}")
            }

            val target = uniqueFile(destinationDirectory, source.name)
            if (move && source.renameTo(target)) {
                results += target
            } else {
                copyRecursively(source, target)
                if (move && !deleteRecursively(source)) {
                    throw IOException("Copie réussie mais suppression de l'original impossible : ${source.name}")
                }
                results += target
            }
        }
        return results
    }

    fun copyRecursively(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IOException("Impossible de créer ${destination.absolutePath}")
            }
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(destination, child.name))
            }
            destination.setLastModified(source.lastModified())
            return
        }

        destination.parentFile?.mkdirs()
        BufferedInputStream(FileInputStream(source), BUFFER_SIZE).use { input ->
            BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { output ->
                input.copyTo(output, BUFFER_SIZE)
            }
        }
        destination.setLastModified(source.lastModified())
    }

    fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return !file.exists() || file.delete()
    }

    fun zip(sources: List<File>, destination: File) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE)).use { zip ->
            val usedNames = hashSetOf<String>()
            sources.forEach { source ->
                val rootName = uniqueEntryName(source.name, usedNames)
                addToZip(source, rootName, zip)
            }
        }
    }

    private fun addToZip(source: File, entryName: String, zip: ZipOutputStream) {
        val normalizedName = entryName.replace(File.separatorChar, '/')
        if (source.isDirectory) {
            val children = source.listFiles().orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry("$normalizedName/"))
                zip.closeEntry()
            } else {
                children.forEach { child ->
                    addToZip(child, "$normalizedName/${child.name}", zip)
                }
            }
            return
        }

        val entry = ZipEntry(normalizedName).apply { time = source.lastModified() }
        zip.putNextEntry(entry)
        BufferedInputStream(FileInputStream(source), BUFFER_SIZE).use { input ->
            input.copyTo(zip, BUFFER_SIZE)
        }
        zip.closeEntry()
    }

    fun unzip(zipFile: File, destinationDirectory: File) {
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw IOException("Impossible de créer ${destinationDirectory.absolutePath}")
        }
        val destinationCanonical = destinationDirectory.canonicalFile

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(destinationDirectory, entry.name).canonicalFile
                if (target.path != destinationCanonical.path &&
                    !target.path.startsWith(destinationCanonical.path + File.separator)
                ) {
                    throw IOException("Archive dangereuse : ${entry.name}")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE).use { output ->
                        zip.copyTo(output, BUFFER_SIZE)
                    }
                    if (entry.time > 0) target.setLastModified(entry.time)
                }
                zip.closeEntry()
            }
        }
    }

    fun uniqueFile(parent: File, requestedName: String): File {
        var candidate = File(parent, requestedName)
        if (!candidate.exists()) return candidate

        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { folderSize(it) } ?: 0L
    }

    private fun isInside(candidate: File, parent: File): Boolean {
        val candidatePath = candidate.canonicalFile.path
        val parentPath = parent.canonicalFile.path
        return candidatePath == parentPath || candidatePath.startsWith(parentPath + File.separator)
    }

    private fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        var index = 1
        while (!used.add("$name ($index)")) index++
        return "$name ($index)"
    }
}
