package app.zakhm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
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

    private val actions = ScreenActions(
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
        toggleRecording = {},
        shareRecording = {},
        startUpdate = {},
        dismissUpdate = {},
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
                Column(Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SettingsContent(state(), settings.copy(language = if (arabic) AppLanguage.ARABIC else AppLanguage.ENGLISH), actions, open)
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
        // Closed groups say what they're set to.
        compose.onNodeWithText("OBD-II").assertExists()
        compose.onNodeWithText("km/h · Auto").assertExists()
        compose.onNodeWithText("0 saved").assertExists()
        save("settings-closed")
    }

    @Test
    @Config(qualifiers = "w432dp-h960dp-port-xxhdpi")
    fun settingsOpenOneGroupAtATime() {
        showSettings(null)
        compose.onNodeWithText("Display").performClick()
        compose.onNodeWithText("Portrait").assertExists()
        compose.onNodeWithText("G-force").performClick()
        compose.onNodeWithText("Portrait").assertDoesNotExist()
        compose.onNodeWithText("Felt by driver").assertExists()
        save("settings-g-open")
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
