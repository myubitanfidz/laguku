package com.laguku.player

import com.laguku.player.service.ActiveOutputRoute
import com.laguku.player.service.AlarmScheduler
import com.laguku.player.service.AudioDeviceProfile
import com.laguku.player.service.BluetoothProfileManager
import com.laguku.player.service.BluetoothSettings
import com.laguku.player.service.ClickGesture
import com.laguku.player.service.DeviceRouteType
import com.laguku.player.service.MediaAction
import com.laguku.player.service.MultiClickMediaMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class BluetoothAndAutomationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun bluetoothProfileManagerSavesByProductNameFallsBackByTypeAndEnforcesLatencyOnlyOnBt() {
        val storeFile = File(tempFolder.root, "bt_profiles.json")
        val manager = BluetoothProfileManager(storeFile)
        manager.updateSettings(BluetoothSettings(autoPlayOnConnect = true, autoPauseOnDisconnect = true, defaultBtLatencyOffsetMs = -180L, btPreRollEnabled = true))

        val xm5Route = ActiveOutputRoute("BT:WF-1000XM5", "WF-1000XM5", DeviceRouteType.BLUETOOTH)
        val curves = FloatArray(10) { i -> if (i == 0) 6f else 0f }
        manager.saveProfile(
            AudioDeviceProfile(
                deviceKey = xm5Route.deviceKey,
                displayName = xm5Route.displayName,
                deviceType = DeviceRouteType.BLUETOOTH,
                eqEnabled = true,
                eqBandsDb = curves,
                crossfeedLevel = 0.4f,
                safeStartVolumePercent = 45,
                lyricLatencyOffsetMs = -220L
            )
        )

        // Fallback type profile for any USB DAC
        manager.saveProfile(
            AudioDeviceProfile(
                deviceKey = BluetoothProfileManager.fallbackKeyForType(DeviceRouteType.WIRED_USB),
                displayName = "Wired / USB",
                deviceType = DeviceRouteType.WIRED_USB,
                eqEnabled = true,
                eqBandsDb = FloatArray(10) { -2f },
                crossfeedLevel = 0.25f,
                safeStartVolumePercent = 60,
                lyricLatencyOffsetMs = -999L // Must still return 0ms on wired!
            )
        )

        // Reload from disk in new manager instance
        val reloaded = BluetoothProfileManager(storeFile)
        val loadedXm5 = reloaded.getProfileForRoute(xm5Route)
        assertTrue(loadedXm5.eqEnabled)
        assertArrayEquals(curves, loadedXm5.eqBandsDb, 0.001f)
        assertEquals(0.4f, loadedXm5.crossfeedLevel, 0.001f)
        assertEquals(45, loadedXm5.safeStartVolumePercent)
        assertEquals(-220L, reloaded.getEffectiveLyricLatencyOffsetMs(xm5Route))

        // Unknown BT device uses defaultBtLatencyOffsetMs (-180ms)
        val unknownBt = ActiveOutputRoute("BT:AirPods", "AirPods", DeviceRouteType.BLUETOOTH)
        assertEquals(-180L, reloaded.getEffectiveLyricLatencyOffsetMs(unknownBt))

        // Wired USB DAC resolves fallback EQ/crossfeed, but lyric latency offset strictly 0ms
        val dacRoute = ActiveOutputRoute("USB:FiiO KA11", "FiiO KA11", DeviceRouteType.WIRED_USB)
        val loadedDac = reloaded.getProfileForRoute(dacRoute)
        assertEquals(0.25f, loadedDac.crossfeedLevel, 0.001f)
        assertEquals(0L, reloaded.getEffectiveLyricLatencyOffsetMs(dacRoute))

        val speakerRoute = ActiveOutputRoute("TYPE:SPEAKER", "Speaker", DeviceRouteType.SPEAKER)
        assertEquals(0L, reloaded.getEffectiveLyricLatencyOffsetMs(speakerRoute))
    }

    @Test
    fun multiClickMapperHandlesSingleDoubleTripleLongPressAndCustomMappings() {
        val mapper = MultiClickMediaMapper()

        // Single click flushed after 380ms window
        assertNull(mapper.onKeyDown(100L))
        assertNull(mapper.onKeyUp(140L))
        assertNull(mapper.flushPending(300L))
        assertEquals(MediaAction.PLAY_PAUSE, mapper.flushPending(550L))

        // Double click
        mapper.onKeyDown(1000L)
        mapper.onKeyUp(1040L)
        mapper.onKeyDown(1180L)
        assertNull(mapper.onKeyUp(1220L))
        assertEquals(MediaAction.NEXT, mapper.flushPending(1650L))

        // Triple click fires immediately on 3rd up
        mapper.onKeyDown(2000L)
        mapper.onKeyUp(2030L)
        mapper.onKeyDown(2150L)
        mapper.onKeyUp(2180L)
        mapper.onKeyDown(2300L)
        assertEquals(MediaAction.PREVIOUS, mapper.onKeyUp(2330L))

        // Long press
        mapper.onKeyDown(3000L)
        assertEquals(MediaAction.CYCLE_EQ_PRESET, mapper.onKeyUp(3750L))

        // Custom remapping
        mapper.setMapping(ClickGesture.DOUBLE, MediaAction.SEEK_FORWARD_10S)
        mapper.onKeyDown(4000L)
        mapper.onKeyUp(4030L)
        mapper.onKeyDown(4150L)
        mapper.onKeyUp(4180L)
        assertEquals(MediaAction.SEEK_FORWARD_10S, mapper.flushPending(4600L))
    }

    @Test
    fun alarmSchedulerComputesSameDayOrNextDayTriggerMillis() {
        val zone = ZoneId.of("UTC")
        val morningNow = ZonedDateTime.of(2026, 5, 10, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        val sameDayTrigger = AlarmScheduler.computeNextTriggerMillis(morningNow, 7, 15, zone)
        val expectedSameDay = ZonedDateTime.of(2026, 5, 10, 7, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedSameDay, sameDayTrigger)

        val eveningNow = ZonedDateTime.of(2026, 5, 10, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val nextDayTrigger = AlarmScheduler.computeNextTriggerMillis(eveningNow, 7, 0, zone)
        val expectedNextDay = ZonedDateTime.of(2026, 5, 11, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedNextDay, nextDayTrigger)
    }
}
