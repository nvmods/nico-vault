package fr.nicovape.coffredoc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import fr.nicovape.coffredoc.crypto.CryptoManager
import fr.nicovape.coffredoc.util.FileNameUtils
import java.util.concurrent.Executors

/**
 * Point d'entrée dédié aux documents envoyés par NicoFiles.
 * Le fichier n'est jamais déchiffré ni copié en clair : il est lu depuis l'URI
 * temporaire accordée par Android, puis chiffré vers l'emplacement choisi.
 */
class ShareEncryptActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val cryptoManager by lazy { CryptoManager(applicationContext) }

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var authenticateButton: Button
    private lateinit var biometricPrompt: BiometricPrompt

    private var sourceUri: Uri? = null
    private var promptVisible = false
    private var destinationPickerStarted = false

    private val createEncryptedFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destinationUri ->
        destinationPickerStarted = false
        val source = sourceUri
        if (source == null || destinationUri == null) {
            finish()
            return@registerForActivityResult
        }
        encrypt(source, destinationUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildContentView()
        configureBiometricPrompt()

        sourceUri = extractSharedUri(intent)
        if (sourceUri == null) {
            Toast.makeText(this, "Aucun document reçu depuis NicoFiles.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        runCatching { cryptoManager.prepareAuthenticatedKey() }
            .onFailure {
                status.text = "Configure d'abord une empreinte ou un verrouillage sécurisé Android."
                authenticateButton.text = "Ouvrir les réglages de sécurité"
                authenticateButton.setOnClickListener { openSecuritySettings() }
                return
            }

        authenticateButton.setOnClickListener { requestAuthentication() }
    }

    override fun onResume() {
        super.onResume()
        if (sourceUri != null && !promptVisible && !destinationPickerStarted) {
            window.decorView.post { requestAuthentication() }
        }
    }

    override fun onDestroy() {
        if (isFinishing) worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = "Chiffrer avec NicoVault"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Authentification nécessaire pour utiliser la clé de chiffrement."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        authenticateButton = Button(this).apply { text = "S'authentifier" }
        val cancel = Button(this).apply {
            text = "Annuler"
            setOnClickListener { finish() }
        }
        container.addView(title)
        container.addView(status)
        container.addView(progress)
        container.addView(authenticateButton)
        container.addView(cancel)
        setContentView(container)
    }

    private fun configureBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    promptVisible = false
                    chooseDestination()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    promptVisible = false
                    status.text = "Authentification annulée : $errString"
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    status.text = "Empreinte non reconnue. Réessaie."
                }
            }
        )
    }

    private fun requestAuthentication() {
        if (promptVisible || destinationPickerStarted) return
        val authenticators = allowedAuthenticators()
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                status.text = "Aucune empreinte ou sécurité d'écran n'est configurée."
                authenticateButton.text = "Configurer la sécurité"
                authenticateButton.setOnClickListener { openSecuritySettings() }
                return
            }
            else -> {
                status.text = "Authentification biométrique indisponible."
                return
            }
        }

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller NicoVault")
            .setSubtitle("Autoriser le chiffrement du document reçu depuis NicoFiles")
            .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText("Annuler")
        }
        promptVisible = true
        biometricPrompt.authenticate(builder.build())
    }

    private fun chooseDestination() {
        val source = sourceUri ?: return
        destinationPickerStarted = true
        val sourceName = FileNameUtils.displayName(this, source)
        createEncryptedFile.launch(FileNameUtils.encryptedName(sourceName))
    }

    private fun encrypt(source: Uri, destination: Uri) {
        authenticateButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Chiffrement en cours…"
        worker.execute {
            val result = runCatching { cryptoManager.encrypt(source, destination) }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess {
                    Toast.makeText(this, "Document chiffré avec succès.", Toast.LENGTH_LONG).show()
                    finish()
                }.onFailure {
                    status.text = "Échec du chiffrement : ${it.message ?: it::class.java.simpleName}"
                    authenticateButton.isEnabled = true
                    authenticateButton.text = "Réessayer"
                }
            }
        }
    }

    private fun extractSharedUri(sharedIntent: Intent?): Uri? {
        if (sharedIntent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                sharedIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> sharedIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: sharedIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    private fun openSecuritySettings() {
        val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, allowedAuthenticators())
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { startActivity(settingsIntent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }
}
