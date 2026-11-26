package com.laguku.player.service

enum class ClickGesture {
    SINGLE,
    DOUBLE,
    TRIPLE,
    LONG_PRESS
}

enum class MediaAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    SEEK_FORWARD_10S,
    SEEK_BACKWARD_10S,
    CYCLE_EQ_PRESET,
    NONE
}

/**
 * Pure Kotlin multi-click & long-press state machine for TWS/wired media button events
 * (KEYCODE_MEDIA_PLAY_PAUSE / KEYCODE_HEADSETHOOK).
 */
class MultiClickMediaMapper {

    private val lock = Any()
    private val mappings = LinkedHashMap<ClickGesture, MediaAction>().apply {
        put(ClickGesture.SINGLE, MediaAction.PLAY_PAUSE)
        put(ClickGesture.DOUBLE, MediaAction.NEXT)
        put(ClickGesture.TRIPLE, MediaAction.PREVIOUS)
        put(ClickGesture.LONG_PRESS, MediaAction.CYCLE_EQ_PRESET)
    }

    private var clickCount = 0
    private var lastUpMs = 0L
    private var downTimeMs = -1L
    private var longPressTriggered = false

    fun setMapping(gesture: ClickGesture, action: MediaAction) {
        synchronized(lock) {
            mappings[gesture] = action
        }
    }

    fun getMapping(gesture: ClickGesture): MediaAction = synchronized(lock) {
        mappings[gesture] ?: MediaAction.NONE
    }

    fun getAllMappings(): Map<ClickGesture, MediaAction> = synchronized(lock) {
        LinkedHashMap(mappings)
    }

    fun exportStringMap(): Map<String, String> = synchronized(lock) {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in mappings) {
            out[k.name] = v.name
        }
        out
    }

    fun importStringMap(raw: Map<*, *>) {
        synchronized(lock) {
            for ((k, v) in raw) {
                val gesture = runCatching { ClickGesture.valueOf(k.toString()) }.getOrNull() ?: continue
                val action = runCatching { MediaAction.valueOf(v.toString()) }.getOrNull() ?: continue
                mappings[gesture] = action
            }
        }
    }

    fun onKeyDown(nowMs: Long, isLongPressFlag: Boolean = false): MediaAction? {
        synchronized(lock) {
            if (isLongPressFlag) {
                clickCount = 0
                downTimeMs = -1L
                longPressTriggered = true
                return mappings[ClickGesture.LONG_PRESS]
            }
            if (downTimeMs < 0L) {
                downTimeMs = nowMs
                longPressTriggered = false
            } else if (!longPressTriggered && nowMs - downTimeMs >= LONG_PRESS_THRESHOLD_MS) {
                clickCount = 0
                longPressTriggered = true
                return mappings[ClickGesture.LONG_PRESS]
            }
            return null
        }
    }

    fun onKeyUp(nowMs: Long): MediaAction? {
        synchronized(lock) {
            val wasLong = longPressTriggered ||
                (downTimeMs >= 0L && nowMs - downTimeMs >= LONG_PRESS_THRESHOLD_MS)
            downTimeMs = -1L
            if (wasLong) {
                val triggerNow = !longPressTriggered
                longPressTriggered = false
                clickCount = 0
                return if (triggerNow) mappings[ClickGesture.LONG_PRESS] else null
            }

            if (nowMs - lastUpMs > MULTI_CLICK_WINDOW_MS) {
                clickCount = 0
            }
            clickCount++
            lastUpMs = nowMs

            if (clickCount >= 3) {
                clickCount = 0
                return mappings[ClickGesture.TRIPLE]
            }
            return null
        }
    }

    fun flushPending(nowMs: Long): MediaAction? {
        synchronized(lock) {
            if (clickCount == 0 || nowMs - lastUpMs < MULTI_CLICK_WINDOW_MS) {
                return null
            }
            val gesture = when (clickCount) {
                1 -> ClickGesture.SINGLE
                2 -> ClickGesture.DOUBLE
                else -> ClickGesture.TRIPLE
            }
            clickCount = 0
            return mappings[gesture]
        }
    }

    fun resetPending() {
        synchronized(lock) {
            clickCount = 0
            downTimeMs = -1L
            longPressTriggered = false
        }
    }

    companion object {
        const val MULTI_CLICK_WINDOW_MS = 380L
        const val LONG_PRESS_THRESHOLD_MS = 650L
    }
}
