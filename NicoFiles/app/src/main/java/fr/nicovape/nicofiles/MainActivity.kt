package fr.nicovape.nicofiles

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var actionBar: HorizontalScrollView
    private lateinit var pasteButton: Button
    private lateinit var renameButton: Button
    private lateinit var extractButton: Button
    private lateinit var restoreButton: Button
    private lateinit var adapter: FileAdapter
    private lateinit var actions: FileActionController

    private val prefs by lazy { getSharedPreferences("nicofiles", MODE_PRIVATE) }
    private val trash by lazy { TrashManager(this) }
    private val selected = linkedSetOf<String>()
    private var shown: List<File> = emptyList()
    private var current = Environment.getExternalStorageDirectory()

    private var showHidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(value) = prefs.edit().putBoolean("hidden", value).apply()
    private var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) = prefs.edit().putBoolean("grid", value).apply()
    private var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(prefs.getString("sort", SortMode.NAME.name)!!) }
            .getOrDefault(SortMode.NAME)
        set(value) = prefs.edit().putString("sort", value.name).apply()

    private val legacyPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setSupportActionBar(toolbar)

        adapter = FileAdapter(::clickFile, ::toggleSelection) { file ->
            if (trash.isInTrash(file)) trash.displayName(file) else file.name
        }
        recycler.adapter = adapter
        applyLayout()

        actions = FileActionController(
            activity = this,
            trash = trash,
            currentDirectory = { current },
            selectedFiles = ::selectedFiles,
            clearSelection = ::clearSelection,
            refresh = { refresh() },
            setBusy = ::setBusy,
            toast = ::toast
        )
        configureButtons()
        configureBack()
        StorageAccess.requestIfNeeded(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (StorageAccess.hasFullAccess(this)) refresh(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_hidden)?.isChecked = showHidden
        menu.findItem(R.id.menu_empty_trash)?.isVisible = trash.trashDirectory.exists()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> searchDialog()
            R.id.menu_refresh -> refresh()
            R.id.menu_new_folder -> createFolderDialog()
            R.id.menu_sort -> sortDialog()
            R.id.menu_view -> { grid = !grid; applyLayout() }
            R.id.menu_hidden -> { showHidden = !showHidden; refresh() }
            R.id.menu_favorite -> toggleFavorite()
            R.id.menu_recent -> showRecent()
            R.id.menu_storage -> showStorage()
            R.id.menu_permission -> StorageAccess.openSettings(this)
            R.id.menu_empty_trash -> emptyTrashDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    fun requestLegacyPermissions(permissions: Array<String>) {
        legacyPermissions.launch(permissions)
    }

    fun updateActionButtons() {
        val files = selectedFiles()
        actionBar.isVisible = files.isNotEmpty()
        pasteButton.isVisible = actions.hasClipboard
        renameButton.isVisible = files.size == 1
        extractButton.isVisible = files.size == 1 && files.first().extension.equals("zip", true)
        restoreButton.isVisible = files.isNotEmpty() && files.all(trash::isInTrash)
        toolbar.subtitle = if (files.isEmpty()) null else "${files.size} sélectionné(s)"
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        pathText = findViewById(R.id.textPath)
        emptyText = findViewById(R.id.textEmpty)
        recycler = findViewById(R.id.recyclerFiles)
        progress = findViewById(R.id.progress)
        actionBar = findViewById(R.id.actionBar)
        pasteButton = findViewById(R.id.buttonPaste)
        renameButton = findViewById(R.id.actionRename)
        extractButton = findViewById(R.id.actionExtract)
        restoreButton = findViewById(R.id.actionRestore)
    }

    private fun configureButtons() {
        findViewById<Button>(R.id.buttonLocations).setOnClickListener { locationsDialog() }
        findViewById<Button>(R.id.buttonUp).setOnClickListener { current.parentFile?.let(::openDirectory) }
        pasteButton.setOnClickListener { actions.paste() }
        findViewById<Button>(R.id.actionCopy).setOnClickListener { actions.copy(false) }
        findViewById<Button>(R.id.actionMove).setOnClickListener { actions.copy(true) }
        renameButton.setOnClickListener { actions.rename() }
        findViewById<Button>(R.id.actionShare).setOnClickListener { actions.share() }
        findViewById<Button>(R.id.actionZip).setOnClickListener { actions.zip() }
        extractButton.setOnClickListener { actions.extract() }
        findViewById<Button>(R.id.actionVault).setOnClickListener { actions.sendToVault() }
        restoreButton.setOnClickListener { actions.restore() }
        findViewById<Button>(R.id.actionDelete).setOnClickListener { actions.delete() }
    }

    private fun configureBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selected.isNotEmpty() -> clearSelection()
                    current.parentFile?.canRead() == true -> openDirectory(current.parentFile!!)
                    else -> finish()
                }
            }
        })
    }

    private fun clickFile(file: File) {
        if (selected.isNotEmpty()) return toggleSelection(file)
        addRecent(file)
        if (file.isDirectory) openDirectory(file) else actions.open(file)
    }

    private fun openDirectory(directory: File) {
        if (!directory.canRead()) return toast("Dossier inaccessible.")
        current = directory
        refresh()
    }

    private fun refresh(preserveSelection: Boolean = false) {
        lifecycleScope.launch {
            setBusy(true)
            val files = withContext(Dispatchers.IO) {
                current.listFiles().orEmpty()
                    .filter { showHidden || !it.name.startsWith('.') }
                    .sortedWith(comparator())
            }
            shown = files
            if (!preserveSelection) selected.clear()
            else selected.retainAll(files.map(File::getAbsolutePath).toSet())
            pathText.text = current.absolutePath
            emptyText.isVisible = files.isEmpty()
            adapter.submit(files, selected)
            updateActionButtons()
            setBusy(false)
        }
    }

    private fun comparator(): Comparator<File> {
        val collator = Collator.getInstance(Locale.getDefault())
        return Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            when (sortMode) {
                SortMode.NAME -> collator.compare(a.name, b.name)
                SortMode.DATE -> b.lastModified().compareTo(a.lastModified())
                SortMode.SIZE -> b.length().compareTo(a.length())
                SortMode.TYPE -> collator.compare(a.extension, b.extension)
                    .takeIf { it != 0 } ?: collator.compare(a.name, b.name)
            }
        }
    }

    private fun toggleSelection(file: File) {
        if (!selected.add(file.absolutePath)) selected.remove(file.absolutePath)
        adapter.updateSelection(selected)
        updateActionButtons()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.updateSelection(selected)
        updateActionButtons()
    }

    private fun selectedFiles(): List<File> = shown.filter { it.absolutePath in selected }

    private fun createFolderDialog() {
        val input = EditText(this).apply { hint = "Nom du dossier" }
        MaterialAlertDialogBuilder(this).setTitle("Nouveau dossier").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) toast("Nom invalide.")
                else if (!File(current, name).mkdir()) toast("Création impossible.")
                else refresh()
            }.show()
    }

    private fun searchDialog() {
        val input = EditText(this).apply { hint = "Nom ou extension" }
        MaterialAlertDialogBuilder(this).setTitle("Rechercher").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Rechercher") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    setBusy(true)
                    val results = withContext(Dispatchers.IO) {
                        current.walkTopDown().onEnter(File::canRead)
                            .filter { it != current && it.name.contains(query, true) }
                            .take(10_000).toList().sortedWith(comparator())
                    }
                    shown = results
                    selected.clear()
                    pathText.text = "Recherche « $query » dans ${current.absolutePath}"
                    emptyText.isVisible = results.isEmpty()
                    adapter.submit(results, emptySet())
                    updateActionButtons()
                    setBusy(false)
                }
            }.show()
    }

    private fun sortDialog() {
        val labels = arrayOf("Nom", "Date", "Taille", "Type")
        MaterialAlertDialogBuilder(this).setTitle("Trier par")
            .setSingleChoiceItems(labels, sortMode.ordinal) { dialog, which ->
                sortMode = SortMode.entries[which]
                dialog.dismiss()
                refresh()
            }.show()
    }

    private fun locationsDialog() {
        val roots = StorageAccess.roots(this) + trash.trashDirectory
        val labels = roots.map { if (it == trash.trashDirectory) "Corbeille" else it.absolutePath }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Emplacements").setItems(labels) { _, index ->
            openDirectory(roots[index])
        }.show()
    }

    private fun toggleFavorite() {
        val values = prefs.getStringSet("favorites", emptySet())!!.toMutableSet()
        if (!values.add(current.absolutePath)) values.remove(current.absolutePath)
        prefs.edit().putStringSet("favorites", values).apply()
        toast(if (current.absolutePath in values) "Ajouté aux favoris." else "Retiré des favoris.")
    }

    private fun addRecent(file: File) {
        val recent = prefs.getString("recent", "").orEmpty().lineSequence()
            .filter(String::isNotBlank).toMutableList()
        recent.remove(file.absolutePath)
        recent.add(0, file.absolutePath)
        prefs.edit().putString("recent", recent.take(30).joinToString("\n")).apply()
    }

    private fun showRecent() {
        val files = prefs.getString("recent", "").orEmpty().lineSequence()
            .map(::File).filter(File::exists).toList()
        if (files.isEmpty()) return toast("Aucun élément récent.")
        MaterialAlertDialogBuilder(this).setTitle("Récents")
            .setItems(files.map(File::getName).toTypedArray()) { _, index ->
                if (files[index].isDirectory) openDirectory(files[index]) else actions.open(files[index])
            }.show()
    }

    private fun showStorage() {
        val message = StorageAccess.roots(this).joinToString("\n\n") { root ->
            runCatching {
                val stat = StatFs(root.absolutePath)
                "${root.absolutePath}\n${formatBytes(stat.availableBytes)} libres sur ${formatBytes(stat.totalBytes)}"
            }.getOrElse { "${root.absolutePath}\nIndisponible" }
        }
        MaterialAlertDialogBuilder(this).setTitle("Stockage").setMessage(message)
            .setPositiveButton("Fermer", null).show()
    }

    private fun emptyTrashDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Vider la corbeille ?")
            .setMessage("Cette suppression est définitive.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Vider") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { trash.empty() } }
                    result.onSuccess { refresh() }.onFailure { toast("Échec : ${it.message}") }
                }
            }.show()
    }

    private fun applyLayout() {
        recycler.layoutManager = if (grid) GridLayoutManager(this, 2) else LinearLayoutManager(this)
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

    private fun setBusy(value: Boolean) {
        progress.isVisible = value
        recycler.alpha = if (value) 0.55f else 1f
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
