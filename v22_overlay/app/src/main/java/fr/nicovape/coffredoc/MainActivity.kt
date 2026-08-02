package fr.nicovape.coffredoc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.UserNotAuthenticatedException
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import fr.nicovape.coffredoc.crypto.CoffreDocFormatException
import fr.nicovape.coffredoc.crypto.CoffreDocLegacyFormatException
import fr.nicovape.coffredoc.crypto.CoffreDocLegacyKeyUnavailableException
import fr.nicovape.coffredoc.crypto.CryptoManager
import fr.nicovape.coffredoc.util.FileNameUtils
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.text.DecimalFormat
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

class MainActivity : AppCompatActivity() {

    private lateinit var contentContainer: View
    private lateinit var lockOverlay: View
    private lateinit var buttonUnlock: Button
    private lateinit var textLockMessage: TextView
    private lateinit var buttonEncrypt: Button
    private lateinit var buttonDecrypt: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textStatus: TextView

    private lateinit var biometricPrompt: BiometricPrompt

    private val worker = Executors.newSingleThreadExecutor()
    private val cryptoManager by lazy { CryptoManager(applicationContext) }

    private var pendingEncryptSource: Uri? = null
    private var pendingDecryptSource: Uri? = null
    private var pendingAfterAuthentication: (() -> Unit)? = null

    private var isUnlocked = false
    private var authenticationInProgress = false
    private var biometricSetupRequired = false
    private var externalDocumentFlowActive = false
    private var lastAuthenticationElapsed = 0L

    private val chooseFileToEncrypt = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            externalDocumentFlowActive = false
            return@registerForActivityResult
        }

        pendingEncryptSource = uri
        val sourceName = FileNameUtils.displayName(this, uri)
        createEncryptedFile.launch(FileNameUtils.encryptedName(sourceName))
    }

    private val createEncryptedFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destinationUri ->
        externalDocumentFlowActive = false
        val sourceUri = pendingEncryptSource
        pendingEncryptSource = null

        if (sourceUri != null && destinationUri != null) {
            requireAuthentication {
                runCryptoOperation(
                    runningMessage = getString(R.string.encrypting),
                    successPrefix = getString(R.string.encrypted_success),
                    operation = { cryptoManager.encrypt(sourceUri, destinationUri) }
                )
            }
        }
    }

    private val chooseFileToDecrypt = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            externalDocumentFlowActive = false
            return@registerForActivityResult
        }

        pendingDecryptSource = uri
        val encryptedName = FileNameUtils.displayName(this, uri)
        val suggestedName = FileNameUtils.decryptedName(encryptedName)
        createDecryptedFile.launch(suggestedName)
    }

    private val createDecryptedFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destinationUri ->
        externalDocumentFlowActive = false
        val sourceUri = pendingDecryptSource
        pendingDecryptSource = null

        if (sourceUri != null && destinationUri != null) {
            requireAuthentication {
                runCryptoOperation(
                    runningMessage = getString(R.string.decrypting),
                    successPrefix = getString(R.string.decrypted_success),
                    operation = { cryptoManager.decrypt(sourceUri, destinationUri) }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        contentContainer = findViewById(R.id.contentContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        buttonUnlock = findViewById(R.id.buttonUnlock)
        textLockMessage = findViewById(R.id.textLockMessage)
        buttonEncrypt = findViewById(R.id.buttonEncrypt)
        buttonDecrypt = findViewById(R.id.buttonDecrypt)
        progressBar = findViewById(R.id.progressBar)
        textStatus = findViewById(R.id.textStatus)

        cryptoManager.cleanupTemporaryFiles()
        configureBiometricPrompt()
        lockApp(getString(R.string.status_locked))

        runCatching { cryptoManager.prepareAuthenticatedKey() }
            .onFailure { error ->
                biometricSetupRequired = true
                lockApp(
                    getString(
                        R.string.keystore_setup_error,
                        error.message ?: error::class.java.simpleName
                    )
                )
            }

        buttonUnlock.setOnClickListener {
            if (biometricSetupRequired) {
                openBiometricSettings()
            } else {
                requestAuthentication()
            }
        }

        buttonEncrypt.setOnClickListener {
            requireAuthentication {
                externalDocumentFlowActive = true
                chooseFileToEncrypt.launch(arrayOf("*/*"))
            }
        }

        buttonDecrypt.setOnClickListener {
            requireAuthentication {
                externalDocumentFlowActive = true
                chooseFileToDecrypt.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (biometricSetupRequired &&
            BiometricManager.from(this).canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            runCatching { cryptoManager.prepareAuthenticatedKey() }
                .onSuccess {
                    biometricSetupRequired = false
                    buttonUnlock.text = getString(R.string.unlock_button)
                }
        }

        if (!isUnlocked && !authenticationInProgress && !externalDocumentFlowActive && !biometricSetupRequired) {
            window.decorView.post { requestAuthentication() }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !externalDocumentFlowActive && !authenticationInProgress) {
            lockApp(getString(R.string.status_locked))
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            worker.shutdownNow()
        }
        super.onDestroy()
    }

    private fun configureBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticationInProgress = false
                    biometricSetupRequired = false
                    isUnlocked = true
                    lastAuthenticationElapsed = SystemClock.elapsedRealtime()
                    showUnlocked()

                    val action = pendingAfterAuthentication
                    pendingAfterAuthentication = null
                    action?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationInProgress = false
                    pendingAfterAuthentication = null
                    lockApp(getString(R.string.authentication_cancelled, errString))
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    textLockMessage.text = getString(R.string.authentication_failed)
                }
            }
        )
    }

    private fun requireAuthentication(action: () -> Unit) {
        val elapsed = SystemClock.elapsedRealtime() - lastAuthenticationElapsed
        if (isUnlocked && elapsed >= 0L && elapsed < UI_AUTH_VALIDITY_MS) {
            action()
            return
        }

        pendingAfterAuthentication = action
        requestAuthentication()
    }

    private fun requestAuthentication() {
        if (authenticationInProgress) return

        val authenticators = allowedAuthenticators()
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                biometricSetupRequired = true
                lockApp(getString(R.string.biometric_not_enrolled))
                buttonUnlock.text = getString(R.string.configure_security)
                return
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                biometricSetupRequired = true
                lockApp(getString(R.string.biometric_no_hardware))
                buttonUnlock.text = getString(R.string.open_security_settings)
                return
            }

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                lockApp(getString(R.string.biometric_unavailable))
                return
            }

            else -> {
                lockApp(getString(R.string.biometric_unavailable))
                return
            }
        }

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(authenticators)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            promptBuilder.setNegativeButtonText(getString(R.string.cancel))
        }

        authenticationInProgress = true
        biometricPrompt.authenticate(promptBuilder.build())
    }

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    private fun openBiometricSettings() {
        val intent = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                    putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        allowedAuthenticators()
                    )
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                Intent(Settings.ACTION_FINGERPRINT_ENROLL)

            else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    private fun showUnlocked() {
        contentContainer.visibility = View.VISIBLE
        lockOverlay.visibility = View.GONE
        buttonUnlock.text = getString(R.string.unlock_button)
        setBusy(false, getString(R.string.status_ready))
    }

    private fun lockApp(message: String) {
        isUnlocked = false
        lastAuthenticationElapsed = 0L
        contentContainer.visibility = View.INVISIBLE
        lockOverlay.visibility = View.VISIBLE
        textLockMessage.text = message
        buttonEncrypt.isEnabled = false
        buttonDecrypt.isEnabled = false
        progressBar.visibility = View.GONE
    }

    private fun runCryptoOperation(
        runningMessage: String,
        successPrefix: String,
        operation: () -> Long
    ) {
        setBusy(true, runningMessage)

        worker.execute {
            val result = runCatching(operation)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                result.fold(
                    onSuccess = { byteCount ->
                        lastAuthenticationElapsed = SystemClock.elapsedRealtime()
                        val message = "$successPrefix (${formatBytes(byteCount)})."
                        setBusy(false, message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        val message = friendlyError(error)
                        setBusy(false, message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

                        if (generateSequence(error) { it.cause }
                                .any { it is UserNotAuthenticatedException }
                        ) {
                            lockApp(getString(R.string.authentication_expired))
                        }
                    }
                )
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        buttonEncrypt.isEnabled = !busy && isUnlocked
        buttonDecrypt.isEnabled = !busy && isUnlocked
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        textStatus.text = status
    }

    private fun friendlyError(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        val classNames = causes.joinToString(" ") { it::class.java.simpleName }
        val messages = causes.mapNotNull { it.message }.joinToString(" ")

        return when {
            causes.any { it is UserNotAuthenticatedException } ->
                getString(R.string.authentication_expired)

            causes.any { it is CoffreDocLegacyFormatException } ->
                "Ce fichier vient de l'ancienne V1 fondée sur EncryptedFile et n'est pas compatible."

            causes.any { it is CoffreDocLegacyKeyUnavailableException } ->
                "Ce fichier V2 nécessite l'ancienne clé locale, absente de cette installation."

            causes.any { it is CoffreDocFormatException } ->
                "Fichier NicoVault invalide : ${causes.firstNotNullOfOrNull { it.message } ?: "format incorrect"}."

            causes.any { it is AEADBadTagException } ||
                "AEADBadTagException" in classNames ||
                "tag mismatch" in messages.lowercase() ||
                "mac check" in messages.lowercase() ->
                "Échec : fichier altéré ou clé différente. Vérifie qu'il a été chiffré par cette installation de NicoVault."

            causes.any { it is KeyStoreException } ||
                causes.any { it is GeneralSecurityException } ->
                "Échec cryptographique : la clé Android Keystore est absente, invalide ou inaccessible."

            causes.any { it is SecurityException } ->
                "Échec : Android a refusé l'accès au document."

            causes.any { it is IOException } ->
                "Échec d'entrée/sortie : ${causes.firstNotNullOfOrNull { it.message } ?: "document inaccessible"}."

            else ->
                "Échec du traitement : ${error.message ?: error::class.java.simpleName}."
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes octets"

        val units = arrayOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }

        return "${DecimalFormat("0.##").format(value)} ${units[unitIndex]}"
    }

    private companion object {
        const val UI_AUTH_VALIDITY_MS = 4 * 60 * 1000L
    }
}
