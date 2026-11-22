package io.github.kevinnortier.isstracker.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kevinnortier.isstracker.MainActivity
import io.github.kevinnortier.isstracker.R
import io.github.kevinnortier.isstracker.data.settings.NotifyTrigger
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.ui.Format
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** Everything a reminder notification needs, passed through the alarm intent. */
data class ReminderInfo(
    val key: PassKey,
    val satelliteName: String,
    val trigger: NotifyTrigger,
    val riseMillis: Long,
    val riseAzimuthDeg: Double,
    val apexMillis: Long,
    val apexElevationDeg: Double,
    val apexAzimuthDeg: Double,
    val setMillis: Long,
    val setAzimuthDeg: Double,
    val visible: Boolean,
) {
    val triggerMillis: Long get() = if (trigger == NotifyTrigger.RISE) riseMillis else apexMillis

    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_KEY, key.encode())
        putExtra(EXTRA_NAME, satelliteName)
        putExtra(EXTRA_TRIGGER, trigger.name)
        putExtra(EXTRA_RISE, riseMillis)
        putExtra(EXTRA_RISE_AZ, riseAzimuthDeg)
        putExtra(EXTRA_APEX, apexMillis)
        putExtra(EXTRA_APEX_EL, apexElevationDeg)
        putExtra(EXTRA_APEX_AZ, apexAzimuthDeg)
        putExtra(EXTRA_SET, setMillis)
        putExtra(EXTRA_SET_AZ, setAzimuthDeg)
        putExtra(EXTRA_VISIBLE, visible)
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_RISE = "rise"
        private const val EXTRA_RISE_AZ = "rise_az"
        private const val EXTRA_APEX = "apex"
        private const val EXTRA_APEX_EL = "apex_el"
        private const val EXTRA_APEX_AZ = "apex_az"
        private const val EXTRA_SET = "set"
        private const val EXTRA_SET_AZ = "set_az"
        private const val EXTRA_VISIBLE = "visible"

        fun from(key: PassKey, satelliteName: String, trigger: NotifyTrigger, pass: Pass) = ReminderInfo(
            key = key,
            satelliteName = satelliteName,
            trigger = trigger,
            riseMillis = pass.rise.time.toEpochMilli(),
            riseAzimuthDeg = pass.rise.azimuthDeg,
            apexMillis = pass.apex.time.toEpochMilli(),
            apexElevationDeg = pass.apex.elevationDeg,
            apexAzimuthDeg = pass.apex.azimuthDeg,
            setMillis = pass.set.time.toEpochMilli(),
            setAzimuthDeg = pass.set.azimuthDeg,
            visible = pass.visible,
        )

        fun fromIntent(intent: Intent): ReminderInfo? {
            val key = intent.getStringExtra(EXTRA_KEY)?.let(PassKey::decode) ?: return null
            return ReminderInfo(
                key = key,
                satelliteName = intent.getStringExtra(EXTRA_NAME) ?: "ISS",
                trigger = intent.getStringExtra(EXTRA_TRIGGER)
                    ?.let { name -> NotifyTrigger.entries.firstOrNull { it.name == name } }
                    ?: NotifyTrigger.RISE,
                riseMillis = intent.getLongExtra(EXTRA_RISE, 0),
                riseAzimuthDeg = intent.getDoubleExtra(EXTRA_RISE_AZ, 0.0),
                apexMillis = intent.getLongExtra(EXTRA_APEX, 0),
                apexElevationDeg = intent.getDoubleExtra(EXTRA_APEX_EL, 0.0),
                apexAzimuthDeg = intent.getDoubleExtra(EXTRA_APEX_AZ, 0.0),
                setMillis = intent.getLongExtra(EXTRA_SET, 0),
                setAzimuthDeg = intent.getDoubleExtra(EXTRA_SET_AZ, 0.0),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
            )
        }
    }
}

/** Builds and shows pass reminder notifications. */
object ReminderNotifications {

    const val CHANNEL_ID = "pass_reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Pass reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders before the passes you choose"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** True if the app may post notifications (permission granted and not blocked in system settings). */
    fun canNotify(context: Context): Boolean =
        hasPostPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The runtime permission only exists from Android 13; before that notifications are allowed by default. */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun hasPostPermission(context: Context): Boolean =
        !needsRuntimePermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Title line, e.g. "ISS rises in 15 min" or "ISS is highest now". */
    fun title(info: ReminderInfo, now: Instant): String {
        val minutes = ceil(Duration.between(now, Instant.ofEpochMilli(info.triggerMillis)).seconds / 60.0).toInt()
        val whenText = if (minutes <= 0) "now" else "in $minutes min"
        return when (info.trigger) {
            NotifyTrigger.RISE -> "${info.satelliteName} rises $whenText"
            NotifyTrigger.APEX -> "${info.satelliteName} is highest $whenText"
        }
    }

    /** Body text with the pass details. */
    fun body(info: ReminderInfo): String = buildString {
        append("Rises ${Format.time(Instant.ofEpochMilli(info.riseMillis))} in the ${Format.compassPoint(info.riseAzimuthDeg)}, ")
        append("highest ${Format.degrees0(info.apexElevationDeg)} at ${Format.time(Instant.ofEpochMilli(info.apexMillis))} ")
        append("(${Format.compassPoint(info.apexAzimuthDeg)}), ")
        append("sets ${Format.time(Instant.ofEpochMilli(info.setMillis))} in the ${Format.compassPoint(info.setAzimuthDeg)}.")
        if (info.visible) append(" Visible to the naked eye.")
    }

    fun showReminder(context: Context, info: ReminderInfo, now: Instant = Instant.now()) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_satellite)
            .setContentTitle(title(info, now))
            .setContentText(body(info))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(info)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(info.triggerMillis)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setTimeoutAfter((info.setMillis - now.toEpochMilli()).coerceAtLeast(60_000))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked in canNotify()
        NotificationManagerCompat.from(context).notify(notificationId(info.key), notification)
    }

    fun cancel(context: Context, key: PassKey) {
        NotificationManagerCompat.from(context).cancel(notificationId(key))
    }

    /** Stable id per pass, so a reminder can be updated or cancelled. */
    fun notificationId(key: PassKey): Int = (key.riseMillis / 1000).toInt() xor (key.noradId shl 8)

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
