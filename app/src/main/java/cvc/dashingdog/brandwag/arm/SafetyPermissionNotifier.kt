package cvc.dashingdog.brandwag.arm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cvc.dashingdog.brandwag.MainActivity
import cvc.dashingdog.brandwag.R
import cvc.dashingdog.brandwag.data.repository.SafetyPermissionBlipRepository

/**
 * Plain informational notifications for blip warnings and auto-disarm
 * events - deliberately NOT routed through AlarmForegroundService (no
 * sound/vibration/full-screen). Stable per-permission-type IDs so a
 * repeated blip re-posts rather than stacks, matching the same pattern
 * 4f will use for degraded-state re-alerting.
 */
object SafetyPermissionNotifier {

    private const val CHANNEL_ID = "safety_permission_status"
    private const val NOTIFICATION_ID_BATTERY = 2001
    private const val NOTIFICATION_ID_DND = 2002

    private fun idFor(type: SafetyPermissionBlipRepository.PermissionType) = when (type) {
        SafetyPermissionBlipRepository.PermissionType.BATTERY_EXEMPTION -> NOTIFICATION_ID_BATTERY
        SafetyPermissionBlipRepository.PermissionType.DND_ACCESS -> NOTIFICATION_ID_DND
    }

    fun notifyFirstBlip(context: Context, type: SafetyPermissionBlipRepository.PermissionType) {
        val (title, body) = when (type) {
            SafetyPermissionBlipRepository.PermissionType.BATTERY_EXEMPTION ->
                context.getString(R.string.blip_battery_first_title) to context.getString(R.string.blip_battery_first_body)
            SafetyPermissionBlipRepository.PermissionType.DND_ACCESS ->
                context.getString(R.string.blip_dnd_first_title) to context.getString(R.string.blip_dnd_first_body)
        }
        post(context, idFor(type), title, body)
    }

    fun notifyAutoDisarmed(context: Context, type: SafetyPermissionBlipRepository.PermissionType) {
        val (title, body) = when (type) {
            SafetyPermissionBlipRepository.PermissionType.BATTERY_EXEMPTION ->
                context.getString(R.string.blip_battery_disarm_title) to context.getString(R.string.blip_battery_disarm_body)
            SafetyPermissionBlipRepository.PermissionType.DND_ACCESS ->
                context.getString(R.string.blip_dnd_disarm_title) to context.getString(R.string.blip_dnd_disarm_body)
        }
        post(context, idFor(type), title, body)
    }

    private fun post(context: Context, notificationId: Int, title: String, body: String) {
        createChannelIfNeeded(context)

        // Plain "open the app" intent - deliberately carries no permission-request extra.
        // A stale notification tapped days later (after a fresh arm/disarm cycle, possibly
        // even a different day's burn) must never auto-trigger a permission flow or any
        // arm-adjacent side effect on its own - it just brings the user to the app, where
        // a real "Check Permissions" action (button, once built) lets them act deliberately.
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = android.app.PendingIntent.getActivity(
            context, notificationId, contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brandwag)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }
    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.safety_permission_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.safety_permission_channel_description)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}