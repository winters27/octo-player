package app.winters.octo.desktop.lyrics

import app.winters.octo.desktop.audio.busIsBluetooth
import app.winters.octo.desktop.audio.endpointGuidOf
import app.winters.octo.desktop.audio.isBluetoothOutput
import app.winters.octo.desktop.audio.linuxSinkIsBluetooth
import app.winters.octo.desktop.audio.macDeviceIsBluetooth
import app.winters.octo.desktop.audio.nameSaysBluetooth
import app.winters.octo.desktop.audio.registryString
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.OutputDevice
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Brandon: on average the words need to show 0.3 s earlier on a wired or
// built-in output and 0.5 s earlier on Bluetooth. An output nobody set
// starts there, by what the system says the output is; one that was set
// keeps its timing, 0 included.
class OutputTimingTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private val speakers = OutputDevice("wasapi:{0.0.0.00000000}.{5b42d98f-cb8c-4411-85b3-0a754da6d750}", "Speakers (Realtek(R) Audio)")
    private val buds = OutputDevice("wasapi:{0.0.0.00000000}.{90677454-4a08-4ae8-9ad0-78cef86459b7}", "Headphones (WH-1000XM4)")

    // What `reg query` prints for one value.
    private fun reg(value: String, data: String) =
        "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render\\{x}\\Properties\r\n    $value    REG_SZ    $data\r\n\r\n"

    @Test
    fun windowsSaysWhichEndpointIsBluetooth() {
        assertEquals("{90677454-4a08-4ae8-9ad0-78cef86459b7}", endpointGuidOf(buds.id))
        assertNull(endpointGuidOf("spk"))
        assertNull("a recording endpoint is not an output", endpointGuidOf("wasapi:{0.0.1.00000000}.{90677454-4a08-4ae8-9ad0-78cef86459b7}"))
        assertEquals("BTHENUM", registryString(reg("{a45c254e-df1c-4efd-8020-67d146a850e0},24", "BTHENUM")))
        assertNull(registryString("ERROR: The system was unable to find the specified registry key or value."))
        assertTrue(busIsBluetooth("BTHENUM"))
        assertTrue(busIsBluetooth("BTHLEDEVICE"))
        assertTrue(busIsBluetooth("{1}.BTHENUM\\{0000110B-0000-1000-8000-00805F9B34FB}_LOCALMFG&0002"))
        assertFalse(busIsBluetooth("HDAUDIO"))
        assertFalse(busIsBluetooth("{1}.USB\\VID_1235&PID_8219&MI_00"))
        assertFalse(busIsBluetooth(null))

        val asked = mutableListOf<List<String>>()
        fun ask(command: List<String>): String? {
            asked += command
            val guid = command[2].substringAfter("Render\\").substringBefore("\\")
            return reg(command.last(), if (guid == endpointGuidOf(buds.id)) "BTHENUM" else "HDAUDIO")
        }
        assertTrue(isBluetoothOutput(buds, DesktopOs.Windows, ::ask))
        assertFalse(isBluetoothOutput(speakers, DesktopOs.Windows, ::ask))
        assertEquals("reg", asked.first().first())
        assertFalse("nothing playing", isBluetoothOutput(null, DesktopOs.Windows, ::ask))
        assertFalse("the system cannot be asked", isBluetoothOutput(buds, DesktopOs.Windows) { null })
    }

    @Test
    fun macAndLinuxSayItTheirOwnWay() {
        val profiler = """{"SPAudioDataType":[{"_name":"coreaudio_device","_items":[
            {"_name":"MacBook Pro Speakers","coreaudio_device_transport":"coreaudio_device_type_builtin"},
            {"_name":"WH-1000XM4","coreaudio_device_transport":"coreaudio_device_type_bluetooth"}]}]}"""
        assertTrue(macDeviceIsBluetooth(profiler, "WH-1000XM4"))
        assertFalse(macDeviceIsBluetooth(profiler, "MacBook Pro Speakers"))
        assertFalse(macDeviceIsBluetooth("not json", "WH-1000XM4"))
        assertTrue(linuxSinkIsBluetooth("bluez_output.AC_80_0A_12_34_56.1\n"))
        assertFalse(linuxSinkIsBluetooth("alsa_output.pci-0000_00_1f.3.analog-stereo"))
        assertTrue(isBluetoothOutput(OutputDevice("default", "default"), DesktopOs.Linux) { "bluez_sink.AC_80.a2dp_sink" })
        assertTrue(nameSaysBluetooth("Headphones (AirPods Pro)"))
        assertFalse(nameSaysBluetooth("Speakers (Realtek(R) Audio)"))
    }

    @Test
    fun anOutputNobodySetStartsFromItsAutomaticTiming() {
        assertEquals(OutputTiming(-300, automatic = true), outputTimingOf(null, bluetooth = false))
        assertEquals(OutputTiming(-500, automatic = true), outputTimingOf(null, bluetooth = true))
        assertEquals(OutputTiming(0, automatic = false), outputTimingOf(0, bluetooth = true))
        assertEquals("For every song on this output. Automatic: 0.5 s earlier, the usual for Bluetooth.", outputTimingAbout(bluetooth = true, automatic = true))
        assertEquals("For every song on this output. Automatic: 0.3 s earlier, the usual for speakers or a cable.", outputTimingAbout(bluetooth = false, automatic = true))
        assertEquals("For every song on this output.", outputTimingAbout(bluetooth = true, automatic = false))
    }

    @Test
    fun theModelFollowsTheOutputAndKeepsWhatWasSet() {
        val silent = SilentPlayer()
        val state = MutableStateFlow(PlayerState(playingOn = speakers))
        val player = object : DesktopPlayer by silent {
            override val state: StateFlow<PlayerState> = state
        }
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val sources = LyricsSources({ null }, http, OnlineLyrics(http, "http://localhost/".toHttpUrl()), settings)
        val model = LyricsModel(player, sources, settings, scope, route = { it == buds })
        waitFor { !model.bluetooth.value }
        assertEquals(-300L, model.outputOffset(speakers.id))

        // Earbuds come on: the words move to their automatic timing.
        state.value = PlayerState(playingOn = buds)
        waitFor { model.bluetooth.value }
        assertEquals(OutputTiming(-500, automatic = true), model.outputTiming(buds.id))

        // Moved from there, and back to 0: 0 is kept, not the automatic one.
        model.stepOutput(buds.id, 1)
        assertEquals(OutputTiming(-450, automatic = false), model.outputTiming(buds.id))
        repeat(9) { model.stepOutput(buds.id, 1) }
        assertEquals(OutputTiming(0, automatic = false), model.outputTiming(buds.id))
        assertEquals(0L, settings.current.lyrics.outputOffsets[buds.id])

        // Use automatic goes back.
        model.resetOutput(buds.id)
        assertEquals(OutputTiming(-500, automatic = true), model.outputTiming(buds.id))
        // The speakers were never touched.
        assertNull(settings.current.lyrics.outputOffsets[speakers.id])
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("waited too long", what())
    }
}
