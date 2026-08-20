package fr.nicovape.nicofiles

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Conteneur racine qui garde automatiquement l'interface hors des zones
 * réservées au système : barre d'état, encoche/caméra et navigation Android.
 *
 * Android 15 impose l'affichage edge-to-edge aux applications ciblant API 35.
 * On conserve donc l'edge-to-edge moderne, mais on applique les insets réels
 * du téléphone comme padding au contenu de NicoFiles.
 */
class SystemBarsLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom
            )

            windowInsets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }
}
