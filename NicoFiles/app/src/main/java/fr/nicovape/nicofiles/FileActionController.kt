package fr.nicovape.nicofiles

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileActionController(
    private val activity: MainActivity,
    private val trash: TrashManager,
    private val currentDirectory: () -> File,
    private val selectedFiles: () -> List<File>,
    private val clearSelection: () -> Unit,
    private val refresh: () -> Unit,
    private val setBusy: (Boolean) -> Unit,
    private val toast: (String) -> Unit
) {
    private var clipboard: ClipboardOperation? = null
    val hasClipboard: Boolean get() = clipboard != null

    fun copy(move: Boolean) {
        val files = selectedFiles()
        if (files.isEmpty()) return
        clipboard = ClipboardOperation(files, move)
        clearSelection()
        toast(if (move) "Prêt à déplacer." else "Prêt à copier.")
        activity.updateActionButtons()
    }

    fun paste() {
        val operation = clipboard ?: return
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                runCatching { FileOperations.copyOrMove(operation.files, currentDirectory(), operation.move) }
            }
            setBusy(false)
            result.onSuccess {
                clipboard = null
                toast(if (operation.move) "Déplacement terminé." else "Copie terminée.")
                refresh()
            }.onFailure { toast("Échec : ${it.message}") }
        }
    }

    fun rename() {
        val file = selectedFiles().singleOrNull() ?: return
        val input = EditText(activity).apply { setText(file.name); setSelection(text.length) }
        MaterialAlertDialogBuilder(activity).setTitle("Renommer").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Renommer") { _, _ ->
                val name = input.text.toString().trim()
                val target = File(file.parentFile, name)
                when {
                    !validName(name) -> toast("Nom invalide.")
                    target.exists() -> toast("Ce nom existe déjà.")
                    !file.renameTo(target) -> toast("Renommage impossible.")
                    else -> { clearSelection(); refresh() }
                }
            }.show()
    }

    fun delete() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val inTrash = files.all(trash::isInTrash)
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (inTrash) "Supprimer définitivement ?" else "Mettre à la corbeille ?")
            .setMessage("${files.size} élément(s)")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Confirmer") { _, _ ->
                activity.lifecycleScope.launch {
                    setBusy(true)
                    val result = withContext(Dispatchers.IO) {
                        runCatching { if (inTrash) trash.deletePermanently(files) else trash.moveToTrash(files) }
                    }
                    setBusy(false)
                    result.onSuccess { clearSelection(); refresh() }
                        .onFailure { toast("Échec : ${it.message}") }
                }
            }.show()
    }

    fun restore() {
        val files = selectedFiles().filter(trash::isInTrash)
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) { runCatching { trash.restore(files) } }
            setBusy(false)
            result.onSuccess { clearSelection(); refresh() }
                .onFailure { toast("Restauration impossible : ${it.message}") }
        }
    }

    fun zip() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destination = FileOperations.uniqueFile(currentDirectory(), "archive_$stamp.zip")
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) { runCatching { FileOperations.zip(files, destination) } }
            setBusy(false)
            result.onSuccess { clearSelection(); refresh() }
                .onFailure { destination.delete(); toast("ZIP impossible : ${it.message}") }
        }
    }

    fun extract() {
        val file = selectedFiles().singleOrNull()?.takeIf { it.extension.equals("zip", true) } ?: return
        val destination = FileOperations.uniqueFile(currentDirectory(), file.nameWithoutExtension.ifBlank { "archive" })
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) { runCatching { FileOperations.unzip(file, destination) } }
            setBusy(false)
            result.onSuccess { clearSelection(); refresh() }
                .onFailure { FileOperations.deleteRecursively(destination); toast("Extraction impossible : ${it.message}") }
        }
    }

    fun share() {
        val files = selectedFiles().filter(File::isFile)
        if (files.isEmpty()) return toast("Sélectionne un fichier.")
        val uris = ArrayList(files.map(::uriFor))
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = mime(files.first())
            putExtra(Intent.EXTRA_STREAM, uris.first())
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(activity.contentResolver, "NicoFiles", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        activity.startActivity(Intent.createChooser(intent, "Partager avec"))
    }

    fun sendToVault() {
        val file = selectedFiles().singleOrNull()?.takeIf(File::isFile)
            ?: return toast("Sélectionne un seul fichier. Utilise ZIP pour plusieurs éléments.")
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(activity.getString(R.string.vault_package))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(activity.contentResolver, "NicoVault", uri)
        }
        try { activity.startActivity(intent) }
        catch (_: ActivityNotFoundException) { toast("NicoVault compatible non trouvé.") }
    }

    fun open(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), mime(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { activity.startActivity(Intent.createChooser(intent, "Ouvrir avec")) }
        catch (_: ActivityNotFoundException) { toast("Aucune application compatible.") }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)

    private fun mime(file: File): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name
}
