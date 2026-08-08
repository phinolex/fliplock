package com.fliplock.cover.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils

/**
 * Detection de l'activation du service d'accessibilite de FlipLock
 * et ouverture directe de la page de reglages correspondante.
 */
object AccessibilityStatus {

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, FlipLockAccessibilityService::class.java.name)

    /** Vrai si l'utilisateur a autorise le service dans les reglages Android. */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = componentName(context)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val parsed = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    /** Vrai si le service est reellement connecte (donc pret a verrouiller). */
    val isServiceConnected: Boolean
        get() = FlipLockAccessibilityService.isConnected

    fun openAccessibilitySettings(context: Context) {
        val flat = componentName(context).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Certaines surcouches (dont One UI) savent selectionner directement le service.
            putExtra(EXTRA_FRAGMENT_ARG_KEY, flat)
            putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, flat) },
            )
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Page « Infos sur l'appli » : necessaire pour lever les « parametres restreints ». */
    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
