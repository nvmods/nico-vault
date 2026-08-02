package fr.nicovape.nicofiles

import android.content.Context
import android.os.Environment
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import java.io.File

/** Affiche un chemin Android sous forme de titre + fil d'Ariane lisible. */
class FriendlyPathTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        val raw = text?.toString().orEmpty()
        val formatted = when {
            raw.startsWith("/") -> formatDirectory(raw)
            raw.startsWith("Recherche «") -> formatSearch(raw)
            else -> null
        }
        super.setText(formatted ?: text, type)
    }

    private fun formatDirectory(path: String): SpannableString {
        val file = File(path)
        val title = when {
            path == Environment.getExternalStorageDirectory().absolutePath -> "Stockage interne"
            file.name.isBlank() -> "Racine"
            else -> file.name
        }
        return styled(title, friendlyPath(path))
    }

    private fun formatSearch(raw: String): SpannableString {
        val match = Regex("Recherche «(.*)» dans (.*)").matchEntire(raw)
            ?: return styled("Recherche", raw)
        val query = match.groupValues[1]
        val path = match.groupValues[2]
        return styled("Recherche : $query", "Dans ${friendlyPath(path)}")
    }

    private fun friendlyPath(path: String): String {
        val storage = Environment.getExternalStorageDirectory().absolutePath
        if (path == storage) return "Stockage interne"
        if (path.startsWith("$storage/")) {
            val relative = path.removePrefix("$storage/")
                .split('/')
                .filter(String::isNotBlank)
                .joinToString("  ›  ")
            return "Stockage interne  ›  $relative"
        }
        return path.trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("  ›  ")
            .ifBlank { "Racine" }
    }

    private fun styled(title: String, subtitle: String): SpannableString {
        val value = SpannableString("$title\n$subtitle")
        val titleEnd = title.length
        value.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            titleEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        value.setSpan(
            RelativeSizeSpan(0.78f),
            titleEnd + 1,
            value.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        value.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.on_surface_variant)),
            titleEnd + 1,
            value.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return value
    }
}
