package fr.nicovape.nicofiles

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class TrashManager(context: Context) {
    private val preferences = context.getSharedPreferences("nicofiles_trash", Context.MODE_PRIVATE)
    val trashDirectory: File = File(Environment.getExternalStorageDirectory(), ".NicoFilesTrash")

    fun isInTrash(file: File): Boolean {
        val trash = runCatching { trashDirectory.canonicalPath }.getOrDefault(trashDirectory.absolutePath)
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return path == trash || path.startsWith(trash + File.separator)
    }

    fun moveToTrash(files: List<File>): List<File> {
        if (!trashDirectory.exists() && !trashDirectory.mkdirs()) {
            throw IOException("Impossible de créer la corbeille.")
        }

        val index = readIndex()
        val moved = mutableListOf<File>()
        files.forEach { source ->
            val safeName = source.name.ifBlank { "document" }
            val destination = File(
                trashDirectory,
                "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_$safeName"
            )

            if (!source.renameTo(destination)) {
                FileOperations.copyRecursively(source, destination)
                if (!FileOperations.deleteRecursively(source)) {
                    FileOperations.deleteRecursively(destination)
                    throw IOException("Impossible de supprimer ${source.name} après sa copie.")
                }
            }
            index.put(destination.absolutePath, source.absolutePath)
            moved += destination
        }
        saveIndex(index)
        return moved
    }

    fun restore(files: List<File>): List<File> {
        val index = readIndex()
        val restored = mutableListOf<File>()

        files.forEach { trashed ->
            val originalPath = index.optString(trashed.absolutePath, "")
            val preferred = if (originalPath.isNotBlank()) File(originalPath) else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), cleanName(trashed.name))
            }
            preferred.parentFile?.mkdirs()
            val destination = FileOperations.uniqueFile(preferred.parentFile ?: trashDirectory.parentFile!!, preferred.name)

            if (!trashed.renameTo(destination)) {
                FileOperations.copyRecursively(trashed, destination)
                if (!FileOperations.deleteRecursively(trashed)) {
                    FileOperations.deleteRecursively(destination)
                    throw IOException("Impossible de terminer la restauration de ${trashed.name}.")
                }
            }
            index.remove(trashed.absolutePath)
            restored += destination
        }
        saveIndex(index)
        return restored
    }

    fun deletePermanently(files: List<File>) {
        val index = readIndex()
        files.forEach { file ->
            if (!FileOperations.deleteRecursively(file)) {
                throw IOException("Impossible de supprimer ${file.name}")
            }
            index.remove(file.absolutePath)
        }
        saveIndex(index)
    }

    fun empty() {
        trashDirectory.listFiles()?.let { deletePermanently(it.toList()) }
    }

    fun displayName(file: File): String = cleanName(file.name)

    private fun cleanName(name: String): String {
        val parts = name.split('_', limit = 3)
        return if (parts.size == 3 && parts[0].all(Char::isDigit)) parts[2] else name
    }

    private fun readIndex(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_INDEX, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun saveIndex(index: JSONObject) {
        preferences.edit().putString(KEY_INDEX, index.toString()).apply()
    }

    companion object {
        private const val KEY_INDEX = "index"
    }
}
