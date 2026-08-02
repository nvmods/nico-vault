package fr.nicovape.nicofiles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.util.Date

class FileAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val displayName: (File) -> String
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var files: List<File> = emptyList()
    private var selectedPaths: Set<String> = emptySet()

    fun submit(files: List<File>, selectedPaths: Set<String>) {
        this.files = files
        this.selectedPaths = selectedPaths
        notifyDataSetChanged()
    }

    fun updateSelection(selectedPaths: Set<String>) {
        this.selectedPaths = selectedPaths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val root: View = view.findViewById(R.id.itemRoot)
        private val icon: ImageView = view.findViewById(R.id.imageIcon)
        private val name: TextView = view.findViewById(R.id.textName)
        private val details: TextView = view.findViewById(R.id.textDetails)
        private val selected: TextView = view.findViewById(R.id.textSelected)

        fun bind(file: File) {
            val isSelected = selectedPaths.contains(file.absolutePath)
            root.isActivated = isSelected
            selected.visibility = if (isSelected) View.VISIBLE else View.GONE
            name.text = displayName(file)
            details.text = fileDetails(file)

            val placeholder = iconFor(file)
            if (file.isImage() || file.isVideo()) {
                icon.clearColorFilter()
                ThumbnailLoader.load(file, icon, placeholder)
            } else {
                icon.tag = null
                icon.setImageResource(placeholder)
                val tint = if (file.isDirectory) R.color.primary else R.color.on_surface
                icon.setColorFilter(ContextCompat.getColor(icon.context, tint))
            }

            root.setOnClickListener { onClick(file) }
            root.setOnLongClickListener {
                onLongClick(file)
                true
            }
        }

        private fun fileDetails(file: File): String {
            val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(file.lastModified()))
            return if (file.isDirectory) {
                "Dossier • $modified"
            } else {
                "${formatBytes(file.length())} • $modified"
            }
        }

        private fun iconFor(file: File): Int = when {
            file.isDirectory -> android.R.drawable.ic_menu_agenda
            file.isImage() -> android.R.drawable.ic_menu_gallery
            file.isVideo() -> android.R.drawable.ic_media_play
            file.isAudio() -> android.R.drawable.ic_media_ff
            file.isArchive() -> android.R.drawable.ic_menu_upload
            file.extension.equals("pdf", true) -> android.R.drawable.ic_menu_view
            else -> android.R.drawable.ic_menu_save
        }
    }
}

fun File.isImage(): Boolean = extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
fun File.isVideo(): Boolean = extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
fun File.isAudio(): Boolean = extension.lowercase() in setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")
fun File.isArchive(): Boolean = extension.lowercase() in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "apk")

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes o"
    val units = arrayOf("Ko", "Mo", "Go", "To")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024 && index < units.lastIndex)
    return if (value >= 100) "%.0f %s".format(value, units[index]) else "%.1f %s".format(value, units[index])
}
