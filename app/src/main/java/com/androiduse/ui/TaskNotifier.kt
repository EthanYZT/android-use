package com.androiduse.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.androiduse.MainActivity
import com.androiduse.R

/**
 * 任务提醒通知（用户在别的 App 里也能看到）：
 * - 步数软提示：横幅（IMPORTANCE_HIGH），带"停止任务"按钮；
 * - 交接：横幅，带"在手机上继续"按钮，一键接管；
 * - 完成/中止：普通通知。
 * 按钮都通过 MainActivity（singleTop）的 extra 回到 App 处理，不另起接收器。
 */
object TaskNotifier {
    private const val CH_ALERT = "agent_alert"
    private const val CH_RESULT = "agent_result"
    private const val ID_STEPS = 2
    private const val ID_RESULT = 3

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, ctx.getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, ctx.getString(R.string.notif_channel_result), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context, requestCode: Int, extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra(extra, true)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun stepReminder(ctx: Context, task: String, steps: Int) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(task.take(40))
            .setContentText(TaskAlerts.stepReminderText(steps))
            .setStyle(Notification.BigTextStyle().bigText(TaskAlerts.stepReminderText(steps)))
            .setContentIntent(openApp(ctx, 10))
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.action_stop_task), openApp(ctx, 11, MainActivity.EXTRA_STOP)).build())
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STEPS, n)
    }

    fun handoff(ctx: Context, reason: String) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_STEPS)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.notif_handoff_title))
            .setContentText(TaskAlerts.handoffText(reason))
            .setStyle(Notification.BigTextStyle().bigText(TaskAlerts.handoffText(reason)))
            .setContentIntent(openApp(ctx, 12))
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.action_takeover), openApp(ctx, 13, MainActivity.EXTRA_TAKEOVER)).build())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_RESULT, n)
    }

    fun outcome(ctx: Context, finished: Boolean, summary: String) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_STEPS)
        val n = Notification.Builder(ctx, CH_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(TaskAlerts.outcomeTitle(finished))
            .setContentText(summary.take(120))
            .setStyle(Notification.BigTextStyle().bigText(summary.take(600)))
            .setContentIntent(openApp(ctx, 14))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_RESULT, n)
    }
}
