package com.androiduse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 任务运行期间挂起的前台服务，只为一件事：让系统把本进程当"前台服务级"而不是缓存后台。
 *
 * 2026-09-18 真机复现：App 切后台 5 秒，ColorOS 的 HANS 冻结器就冻住 uid（`onUidFrozenChanged frozen=true`）
 * 并把它加进网络黑名单，正在发的模型请求挂住，切回前台时以 `connection abort` / `Unable to resolve host`
 * 失败、任务中止。有前台服务在，procState 停在 FOREGROUND_SERVICE，不冻结、不断网。
 *
 * 循环本身仍在 MainActivity 的协程里跑（这里不搬），服务只持有通知；任务开始 start、结束 stop。
 * 类型用 specialUse（自动化代理没有更贴切的系统类型；dataSync 有每天 6 小时上限）。
 */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent_task"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_TASK = "task"

        fun start(context: Context, task: String) {
            val i = Intent(context, AgentForegroundService::class.java).putExtra(EXTRA_TASK, task)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val task = intent?.getStringExtra(EXTRA_TASK).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(task), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY // 进程被杀就不要自己爬起来：没有 Activity 协程，服务空转没意义
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(task: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_task), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_task_desc) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.notif_task_running))
            .setContentText(task.take(60))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
