package fr.nicovape.coffredoc.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Fournit les clés AES-256 non exportables conservées par Android Keystore. */
internal class AndroidKeystoreKeyProvider {

    private val lock = Any()

    /**
     * Clé V3 : son utilisation exige une authentification forte récente.
     * La fenêtre de cinq minutes permet le traitement par blocs sans demander une
     * empreinte pour chaque bloc AES-GCM.
     */
    fun getOrCreateAuthenticatedKey(): SecretKey = synchronized(lock) {
        val keyStore = loadKeyStore()

        if (keyStore.containsAlias(AUTHENTICATED_KEY_ALIAS)) {
            return@synchronized keyStore.getKey(AUTHENTICATED_KEY_ALIAS, null) as? SecretKey
                ?: throw GeneralSecurityException(
                    "L'entrée Keystore NicoVault existe mais n'est pas une clé AES."
                )
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            AUTHENTICATED_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(false)
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    /** Ancienne clé V2, conservée uniquement pour relire les fichiers déjà créés. */
    fun getLegacyV2KeyOrNull(): SecretKey? = synchronized(lock) {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(LEGACY_V2_KEY_ALIAS)) return@synchronized null
        keyStore.getKey(LEGACY_V2_KEY_ALIAS, null) as? SecretKey
    }

    private fun loadKeyStore(): KeyStore = KeyStore
        .getInstance(ANDROID_KEYSTORE)
        .apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AUTHENTICATED_KEY_ALIAS = "nicovault_aes256_gcm_v3_auth"
        const val LEGACY_V2_KEY_ALIAS = "coffredoc_aes256_gcm_v2"
        const val KEY_SIZE_BITS = 256
        const val AUTH_VALIDITY_SECONDS = 5 * 60
    }
}
