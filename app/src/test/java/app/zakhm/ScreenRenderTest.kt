package app.zakhm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zakhm.core.CalibrationState
import app.zakhm.core.CarProfile
import app.zakhm.core.CarTheme
import app.zakhm.core.Peaks
import app.zakhm.core.SpeedInput
import app.zakhm.core.SpeedSource
import app.zakhm.core.TelemetrySnapshot
import app.zakhm.core.ThemePreset
import app.zakhm.core.TripSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens without a phone, and saves them to `build/screens` to look
 * at. A failure here means a screen crashed while being composed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-port-xxhdpi")
class ScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun state(
        theme: CarTheme = CarTheme.DEFAULT,
        snapshot: TelemetrySnapshot = driving,
    ) = TelemetryUiState(
        snapshot = snapshot,
        selectedInput = SpeedInput.OBD,
        activeInput = SpeedInput.OBD,
        obdState = ObdState.CONNECTED,
        obdAddress = "00:11:22:33:44:55",
        cars = listOf(CarProfile("a", "Picanto", input = SpeedInput.OBD, theme = theme)),
        activeCarId = "a",
        satellitesUsed = 18,
    )

    private val settings = ScreenSettings(
        useMph = false,
        rotation = RotationMode.AUTO,
        obdDevices = emptyList(),
        scanningForAdapters = false,
        needsBluetoothPermission = false,
        language = AppLanguage.ENGLISH,
    )

    private val actions = actionsWith()

    private fun actionsWith(enableBluetooth: () -> Unit = {}, toggleRecording: () -> Unit = {}) = ScreenActions(
        requestPermission = {},
        calibrate = {},
        resetPeaks = {},
        setUseMph = {},
        setRotation = {},
        setLanguage = {},
        setTheme = { _, _ -> },
        selectInput = {},
        selectObdDevice = {},
        requestBluetooth = {},
        scanForAdapters = {},
        toggleObdConnection = {},
        selectCar = {},
        addCar = {},
        renameCar = { _, _ -> },
        deleteCar = {},
        saveUnknownCarToCurrent = {},
        saveUnknownCarAsNew = {},
        skipUnknownCar = {},
        toggleRecording = toggleRecording,
        shareRecording = {},
        startUpdate = {},
        dismissUpdate = {},
        enableBluetooth = enableBluetooth,
    )

    private fun show(state: TelemetryUiState, screenSettings: ScreenSettings = settings) {
        compose.setContent { TelemetryScreen(state, UpdateState.None, true, screenSettings, actions) }
        compose.waitForIdle()
    }

    /** The owner's phone, a moto g52: 1080 x 2400 pixels at 400 dpi. */
    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun ownersPhone() {
        show(state())
        save("portrait-moto-g52")
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("tap label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun speedFullScreen() {
        compose.setContent { TelemetryScreen(state(), UpdateState.None, true, settings, actions, startFocus = Focus.SPEED) }
        save("focus-speed")
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun gFullScreen() {
        compose.setContent { TelemetryScreen(state(), UpdateState.None, true, settings.copy(gView = GView.DRIVER, gRange = 1.5), actions, startFocus = Focus.G) }
        save("focus-g")
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun gFullScreenLandscape() {
        compose.setContent { TelemetryScreen(state(), UpdateState.None, true, settings, actions, startFocus = Focus.G) }
        save("focus-g-landscape")
    }

    @Test
    fun tappingAnInstrumentFillsTheScreenAndTappingAgainComesBack() {
        show(state())
        compose.onNodeWithText("KM/H").assertExists()
        compose.onAllNodes(hasClickLabel("Show full screen"))[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("KM/H").assertDoesNotExist()
        // Where the speed comes from must stay on screen in full screen too.
        compose.onNodeWithText("OBD-II", substring = true).assertExists()
        compose.onNode(hasClickLabel("Show both again")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("KM/H").assertExists()
    }

    /** The owner's settings: felt by driver, ring at 1.5 G. */
    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun ownersPhoneDriverView() {
        show(state(), settings.copy(gView = GView.DRIVER, gRange = 1.5))
        save("portrait-moto-g52-driver-1.5")
    }

    @Test
    fun driverViewZoomedIn() {
        show(state(), settings.copy(gView = GView.DRIVER, gRange = 0.5))
        save("g-driver-zoom")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun portraitDashboard() {
        show(state())
        save("portrait-en")
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun landscapeDashboard() {
        show(state())
        save("landscape-en")
    }

    @Test
    @Config(qualifiers = "ar-w393dp-h851dp-port-xxhdpi")
    fun arabicDashboard() {
        show(state())
        save("portrait-ar")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-port-xhdpi")
    fun smallPhone() {
        show(state())
        save("portrait-small")
    }

    @Test
    fun calibrating() {
        show(state(snapshot = driving.copy(calibration = CalibrationState.LEARNING_FORWARD, longitudinalG = null, lateralG = null)))
        save("calibrating")
    }

    @Test
    @Config(qualifiers = "ar-w393dp-h851dp-port-xxhdpi")
    fun phoneInHand() {
        show(state(snapshot = driving.copy(phoneInHand = true, longitudinalG = null, lateralG = null, totalG = null)))
        save("phone-in-hand-ar")
    }

    @Test
    fun everyThemeDraws() {
        var current by mutableStateOf(state())
        compose.setContent { TelemetryScreen(current, UpdateState.None, true, settings, actions) }
        ThemePreset.entries.forEach { preset ->
            current = state(theme = preset.theme)
            save("theme-${preset.name.lowercase()}")
        }
    }

    @Test
    fun bluetoothOffSaysSoAndOffersToTurnItOn() {
        var asked = false
        val off = state().copy(obdState = ObdState.BLUETOOTH_OFF, activeInput = SpeedInput.PHONE)
        compose.setContent { TelemetryScreen(off, UpdateState.None, true, settings, actionsWith(enableBluetooth = { asked = true })) }
        compose.waitForIdle()
        compose.onNodeWithText("Bluetooth is off", substring = true).assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Speed source").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Turn on Bluetooth"))
        compose.onNodeWithText("Turn on Bluetooth").performClick()
        assertTrue(asked)
        save("settings-bluetooth-off")
    }

    /** Each G-force style on the dashboard and full screen, saved to look at. */
    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun gForceStyles() {
        var style by mutableStateOf(GStyle.RING)
        var focus by mutableStateOf<Focus?>(null)
        compose.setContent {
            androidx.compose.runtime.key(focus) {
                TelemetryScreen(state(), UpdateState.None, true, settings.copy(gStyle = style), actions, startFocus = focus)
            }
        }
        listOf(GStyle.RING, GStyle.WINDOW).forEach {
            style = it
            focus = null
            compose.onNodeWithText("0.38", substring = true).assertExists()
            save("g-style-${it.name.lowercase()}")
            focus = Focus.G
            save("g-style-${it.name.lowercase()}-full")
        }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun gForceStylesLandscape() {
        var style by mutableStateOf(GStyle.RING)
        compose.setContent { TelemetryScreen(state(), UpdateState.None, true, settings.copy(gStyle = style), actions) }
        save("g-style-ring-land")
        style = GStyle.WINDOW
        save("g-style-window-land")
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun theDashboardStartsAndStopsRecording() {
        var toggled = 0
        var recording by mutableStateOf(false)
        compose.setContent {
            val shown = if (recording) state().copy(recording = true, recordingSeconds = 65) else state()
            TelemetryScreen(shown, UpdateState.None, true, settings, actionsWith(toggleRecording = { toggled++ }))
        }
        compose.onNodeWithContentDescription("Record drive").performClick()
        assertEquals(1, toggled)
        recording = true
        compose.waitForIdle()
        // While recording it says so with the time, and offers to stop.
        compose.onNodeWithText("1:05").assertExists()
        compose.onNodeWithContentDescription("Stop recording").performClick()
        assertEquals(2, toggled)
        save("dashboard-recording")
    }

    @Test
    fun obdRetriesSlowDownWhileTheAdapterStaysAway() {
        assertEquals(3_000L, obdRetryDelayMs(1))
        assertEquals(3_000L, obdRetryDelayMs(OBD_QUICK_RETRIES))
        assertEquals(15_000L, obdRetryDelayMs(OBD_QUICK_RETRIES + 1))
    }

    @Test
    fun settingsSheetWithThemeEditor() {
        show(state())
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Car").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Edit colours"))
        compose.onNodeWithText("Edit colours").performClick()
        compose.waitForIdle()
        save("settings-theme")
    }

    @Test
    fun themeEditor() {
        var theme by mutableStateOf(ThemePreset.BLUE.theme)
        compose.setContent {
            DashTheme(theme) {
                Column(
                    Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeSettings(theme) { changed, _ -> theme = changed }
                }
            }
        }
        compose.onNodeWithText("Edit colours").performClick()
        save("theme-editor")
        compose.onNodeWithText("White").performClick()
        save("theme-editor-white")
        assertEquals(ThemePreset.WHITE.theme, theme)
    }

    private fun showSettings(open: String?, arabic: Boolean = false) {
        compose.setContent {
            DashTheme(CarTheme.DEFAULT) {
                Box(Modifier.fillMaxSize().background(Background).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SettingsScreen(state(), settings.copy(language = if (arabic) AppLanguage.ARABIC else AppLanguage.ENGLISH), actions, onClose = {}, startPage = open)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun settingsFitOneScreenWhenClosed() {
        showSettings(null)
        listOf("How to use Zakhm", "Car", "Speed source", "Display", "G-force", "Drives", "Tools").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // Each group says what it's set to, and the speed source how its connection is.
        compose.onNodeWithText("OBD-II · car speed").assertExists()
        compose.onNodeWithText("km/h · Auto").assertExists()
        compose.onNodeWithText("0 saved").assertExists()
        save("settings-closed")
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun eachGroupOpensItsOwnPageAndBackReturns() {
        showSettings(null)
        compose.onNodeWithText("Display").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Portrait").assertExists()
        compose.onNodeWithText("G-force").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Portrait").assertDoesNotExist()
        compose.onNodeWithText("G-force").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Felt by driver").assertExists()
        save("settings-g-open")
    }

    /** Every group's own page, saved to look at. */
    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun everySettingsPage() {
        var page by mutableStateOf("CAR")
        compose.setContent {
            DashTheme(CarTheme.DEFAULT) {
                Box(Modifier.fillMaxSize().background(Background).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // A fresh screen for each page, opened straight on it.
                    androidx.compose.runtime.key(page) { SettingsScreen(state(), settings, actions, onClose = {}, startPage = page) }
                }
            }
        }
        listOf("CAR", "SOURCE", "DISPLAY", "G_FORCE", "DRIVES", "GUIDE", "TOOLS").forEach {
            page = it
            save("settings-page-${it.lowercase()}")
        }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun settingsListLandscapeUsesTwoColumns() {
        showSettings(null)
        listOf("Car", "Speed source", "Display", "G-force", "Drives", "Tools").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        save("settings-landscape")
    }

    @Test
    @Config(qualifiers = "ar-w432dp-h960dp-port-xxhdpi")
    fun settingsListArabic() {
        showSettings(null, arabic = true)
        save("settings-closed-ar")
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun settingsGuide() {
        showSettings("GUIDE")
        compose.onNodeWithText("Fix the phone in a mount.", substring = true).assertExists()
        save("settings-guide")
    }

    @Test
    @Config(qualifiers = "ar-w432dp-h960dp-port-xxhdpi")
    fun settingsGuideArabic() {
        showSettings("GUIDE", arabic = true)
        save("settings-guide-ar")
    }

    private fun showDrives(trips: List<SavedTrip>) {
        compose.setContent {
            DashTheme(CarTheme.DEFAULT) {
                Column(
                    Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TripsSection(trips, useMph = false, onShare = { _, _ -> }, onDelete = {})
                }
            }
        }
    }

    private fun trip(name: String, minutes: Long, meters: Double) =
        SavedTrip(File(name), TripSummary(1_762_870_000_000 - minutes * 3_600_000, minutes * 60, meters, 31.4, 0.62))

    @Test
    fun savedDrives() {
        showDrives(listOf(trip("a", 12, 8_400.0), trip("b", 95, 121_300.0), trip("c", 4, 900.0), trip("d", 30, 22_000.0)))
        compose.onNodeWithText("Show all (4)").assertExists()
        compose.onAllNodes(hasText("Open on map")).assertCountEquals(3)
        compose.onAllNodes(hasText("Share KMZ")).assertCountEquals(3)
        save("drives")
        compose.onNodeWithText("Show all (4)").performClick()
        compose.onNodeWithText("Show all (4)").assertDoesNotExist()
        compose.onNodeWithText("1 h 35 min · 121.3 km · top 113 km/h · 0.62 G").assertExists()
    }

    @Test
    @Config(qualifiers = "ar-w393dp-h851dp-port-xxhdpi")
    fun savedDrivesArabic() {
        showDrives(listOf(trip("a", 12, 8_400.0)))
        save("drives-ar")
    }

    @Test
    fun noSavedDrives() {
        showDrives(emptyList())
        compose.onNodeWithText("No drives yet.").assertExists()
    }

    /**
     * The README's pictures, with sample readings and drives, at 540 x 1145 pixels like
     * the earlier ones. Saved to `build/screens/readme-*.png` and copied into `docs/images` by hand.
     */
    @Test
    @Config(qualifiers = "w360dp-h763dp-port-hdpi")
    fun readmeEnglish() = readme("en", AppLanguage.ENGLISH)

    @Test
    @Config(qualifiers = "ar-w360dp-h763dp-port-hdpi")
    fun readmeArabic() = readme("ar", AppLanguage.ARABIC)

    private fun readme(name: String, language: AppLanguage) {
        val shown = settings.copy(gView = GView.DRIVER, gRange = 1.5, language = language)
        val drives = listOf(
            SavedTrip(File("a"), TripSummary(1_794_480_000_000, 26 * 60, 18_400.0, 27.8, 0.48)),
            SavedTrip(File("b"), TripSummary(1_794_390_000_000, 12 * 60, 7_900.0, 19.4, 0.36)),
            SavedTrip(File("c"), TripSummary(1_794_300_000_000, 74 * 60, 96_200.0, 33.3, 0.52)),
        )
        var screen by mutableStateOf(0)
        compose.setContent {
            when (screen) {
                0 -> TelemetryScreen(state(), UpdateState.None, true, shown, actions)
                1 -> DashTheme(CarTheme.DEFAULT) {
                    Box(Modifier.fillMaxSize().background(Background).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SettingsScreen(state().copy(trips = drives), shown, actions, onClose = {})
                    }
                }
                else -> DashTheme(CarTheme.DEFAULT) {
                    Column(
                        Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TripsSection(drives, useMph = false, onShare = { _, _ -> }, onDelete = {})
                    }
                }
            }
        }
        save("readme-dashboard-$name")
        screen = 1
        save("readme-settings-$name")
        screen = 2
        save("readme-drives-$name")
    }

    @Test
    fun badNumbersDoNotCrash() {
        show(state(snapshot = driving.copy(speedMps = Double.NaN, totalG = Double.POSITIVE_INFINITY)))
    }

    private companion object {
        val driving = TelemetrySnapshot(
            speedMps = 27.0,
            speedSource = SpeedSource.LIVE,
            speedSigmaMps = 0.2,
            calibration = CalibrationState.READY,
            learningProgress = 1.0,
            longitudinalG = 0.22,
            lateralG = 0.31,
            totalG = 0.38,
            peaks = Peaks(accel = 0.31, brake = 0.48, left = 0.39, right = 0.65),
        )
    }
}
