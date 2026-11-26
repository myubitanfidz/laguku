package com.laguku.player.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class MorningAlarmConfig(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val fadeInSeconds: Int = 30
)

/**
 * Schedules a zero-standby Morning Fade-In Alarm via [AlarmManager].
 * Cancels all pending alarms immediately when disabled so battery impact is strictly zero.
 */
object AlarmScheduler {

    const val ACTION_MORNING_ALARM = "com.laguku.player.action.MORNING_ALARM"
    const val EXTRA_FADE_IN_SEC = "extra_fade_in_sec"
    private const val REQ_MORNING_ALARM = 4101

    fun computeNextTriggerMillis(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val safeHour = hour.coerceIn(0, 23)
        val safeMin = minute.coerceIn(0, 59)
        val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        var target = nowZdt
            .withHour(safeHour)
            .withMinute(safeMin)
            .withSecond(0)
            .withNano(0)
        if (!target.isAfter(nowZdt)) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }

    fun schedule(context: Context, config: MorningAlarmConfig) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_MORNING_ALARM
            putExtra(EXTRA_FADE_IN_SEC, config.fadeInSeconds.coerceIn(5, 120))
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_MORNING_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pi)
        if (!config.enabled) return

        val triggerAt = computeNextTriggerMillis(System.currentTimeMillis(), config.hour, config.minute)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
