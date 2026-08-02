package fr.nicovape.nicofiles

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

object StorageAccess {
    fun hasFullAccess(activity: MainActivity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestIfNeeded(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.storage_permission_title)
                    .setMessage(R.string.storage_permission_message)
                    .setNegativeButton("Plus tard", null)
                    .setPositiveButton("Réglages") { _, _ -> openSettings(activity) }
                    .show()
            }
        } else {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            if (missing.isNotEmpty()) activity.requestLegacyPermissions(missing.toTypedArray())
        }
    }

    fun openSettings(activity: MainActivity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        }
        try { activity.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun roots(activity: MainActivity): List<File> {
        val roots = linkedMapOf<String, File>()
        val internal = Environment.getExternalStorageDirectory()
        roots[internal.absolutePath] = internal
        activity.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            val marker = "${File.separator}Android${File.separator}"
            val cut = appDir.absolutePath.indexOf(marker)
            val root = if (cut > 0) File(appDir.absolutePath.substring(0, cut)) else appDir
            if (root.exists()) roots[root.absolutePath] = root
        }
        return roots.values.toList()
    }
}
