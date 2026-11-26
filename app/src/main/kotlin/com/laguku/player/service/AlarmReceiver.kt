package com.laguku.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives [AlarmScheduler.ACTION_MORNING_ALARM] and starts [PlaybackService] with a 30s volume fade-in.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != AlarmScheduler.ACTION_MORNING_ALARM) return
        val fadeSec = intent.getIntExtra(AlarmScheduler.EXTRA_FADE_IN_SEC, 30)
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_MORNING_ALARM_TRIGGER
            putExtra(AlarmScheduler.EXTRA_FADE_IN_SEC, fadeSec)
        }
        runCatching {
            context.startForegroundService(serviceIntent)
        }
    }
}
