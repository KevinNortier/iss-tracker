package io.github.kevinnortier.isstracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.kevinnortier.isstracker.notify.ReminderNotifications

/** Runs actions that need notification permission, asking for it first when necessary. */
class NotificationPermissionRequester internal constructor(
    private val context: Context,
    private val launch: (onGranted: () -> Unit) -> Unit,
    /** True after the user denied the permission (or notifications are blocked in system settings). */
    val denied: Boolean,
) {
    /** Runs [action] now if notifications are allowed, otherwise asks for permission first. */
    fun runWithPermission(action: () -> Unit) {
        if (ReminderNotifications.canNotify(context)) action() else launch(action)
    }

    /** Opens the system notification settings for this app. */
    fun openSystemSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    // The action to run once the user has answered the dialog (not saved across process death).
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Granted in the dialog, but notifications can still be switched off in system settings.
        val allowed = granted && ReminderNotifications.canNotify(context)
        denied = !allowed
        if (allowed) pending.value?.invoke()
        pending.value = null
    }
    return NotificationPermissionRequester(
        context = context,
        launch = { action ->
            if (ReminderNotifications.needsRuntimePermission()) {
                pending.value = action
                @SuppressLint("InlinedApi") // only reached on Android 13+
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Android 12: there is no dialog; notifications must have been switched off in system settings.
                denied = true
            }
        },
        denied = denied,
    )
}
