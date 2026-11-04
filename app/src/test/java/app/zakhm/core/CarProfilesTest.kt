package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CarProfilesTest {
    private var nextId = 0
    private fun registry(cars: List<CarProfile> = emptyList(), active: String? = null) =
        CarRegistry(cars, active) { "car${nextId++}" }

    private fun hex(text: String) = text.map { "%02X".format(it.code) }.joinToString("")

    private val vin = "1HGCM82633A004352"

    @Test
    fun parsesVinFromCanFrames() {
        val first = "0:490201" + hex(vin.take(3))
        val second = "1:" + hex(vin.substring(3, 10))
        val third = "2:" + hex(vin.substring(10))
        assertEquals(vin, ObdParser.parseVin("014\r$first\r$second\r$third\r\r"))
    }

    @Test
    fun parsesVinFromOlderCarFrames() {
        val frames = listOf(
            "49020100000031".replace("31", hex(vin.take(1))),
            "490202" + hex(vin.substring(1, 5)),
            "490203" + hex(vin.substring(5, 9)),
            "490204" + hex(vin.substring(9, 13)),
            "490205" + hex(vin.substring(13, 17)),
        )
        assertEquals(vin, ObdParser.parseVin(frames.joinToString("\r")))
    }

    @Test
    fun rejectsRepliesWithoutAValidVin() {
        assertNull(ObdParser.parseVin("NO DATA"))
        assertNull(ObdParser.parseVin("?"))
        assertNull(ObdParser.parseVin(""))
        assertNull(ObdParser.parseVin("490201" + hex("TOOSHORT")))
        assertNull(ObdParser.parseVin("410D3C"))
    }

    @Test
    fun alwaysHasACar() {
        val cars = registry()
        assertEquals(1, cars.cars.size)
        assertEquals(CarRegistry.DEFAULT_NAME, cars.active.name)
        assertFalse(cars.delete(cars.active.id))
        assertEquals(1, cars.cars.size)
    }

    @Test
    fun addsSelectsRenamesAndDeletes() {
        val cars = registry()
        val first = cars.active.id
        val second = cars.add("  Camry  ")
        assertEquals("Camry", second.name)
        assertEquals(second.id, cars.active.id)

        assertTrue(cars.select(first))
        assertEquals(first, cars.active.id)
        assertFalse(cars.select("missing"))

        cars.rename(second.id, "Wife's Camry")
        assertEquals("Wife's Camry", cars.cars.last().name)

        assertFalse("deleting another car leaves the active one", cars.delete(second.id))
        assertEquals(first, cars.active.id)

        val third = cars.add("Truck")
        assertTrue("deleting the active car switches away", cars.delete(third.id))
        assertEquals(first, cars.active.id)
    }

    @Test
    fun unknownVinChangesNothingUntilTheUserDecides() {
        val cars = registry()
        cars.rename(cars.active.id, "picanto")
        val before = cars.active
        assertNull(cars.selectKnown(CarIdentity.fromVin(vin)))
        assertEquals(before, cars.active)
        assertEquals(1, cars.cars.size)
    }

    @Test
    fun savingToTheCurrentCarKeepsItsChosenName() {
        val cars = registry()
        cars.rename(cars.active.id, "picanto")
        val id = cars.active.id
        val saved = cars.saveToActive(CarIdentity.fromVin(vin))
        assertEquals(id, saved.id)
        assertEquals("picanto", saved.name)
        assertEquals(vin, CarIdentity.vinOf(saved.identityKey))
        assertEquals(1, cars.cars.size)
    }

    @Test
    fun savingToAnUntouchedDefaultCarNamesIt() {
        val cars = registry()
        assertEquals("Car 004352", cars.saveToActive(CarIdentity.fromVin(vin)).name)
    }

    @Test
    fun savingToTheCurrentCarReplacesItsOldVin() {
        val cars = registry()
        cars.saveToActive(CarIdentity.fromVin(vin))
        val other = "2T1BURHE0JC123456"
        assertEquals(other, CarIdentity.vinOf(cars.saveToActive(CarIdentity.fromVin(other)).identityKey))
        assertNull("the old VIN no longer belongs to any car", cars.selectKnown(CarIdentity.fromVin(vin)))
    }

    @Test
    fun savingAsNewAddsACarAndKnownCarLoadsByItself() {
        val cars = registry()
        cars.updateActive { it.copy(input = SpeedInput.OBD, obdAddress = "AA:BB") }
        val first = cars.saveToActive(CarIdentity.fromVin(vin))

        val other = cars.saveAsNew(CarIdentity.fromVin("2T1BURHE0JC123456"))
        assertNotEquals(first.id, other.id)
        assertEquals(other.id, cars.active.id)
        assertEquals("keeps using the same adapter", "AA:BB", other.obdAddress)
        assertEquals(SpeedInput.OBD, other.input)

        assertEquals(first.id, cars.selectKnown(CarIdentity.fromVin(vin))!!.id)
        assertEquals(first.id, cars.active.id)
        assertEquals(2, cars.cars.size)
    }

    @Test
    fun onlyVinIdentitiesHaveAVin() {
        assertEquals(vin, CarIdentity.fromVin(vin).vin)
        assertNull(CarIdentity.fromModel("Kia", "Picanto", 2017).vin)
        assertNull(CarIdentity.vinOf(null))
    }

    @Test
    fun androidAutoIdentityIgnoresCaseAndMissingYear() {
        val a = CarIdentity.fromModel("Toyota", "Corolla", 2020)
        assertEquals(a.key, CarIdentity.fromModel(" TOYOTA", "corolla ", 2020).key)
        assertEquals("Toyota Corolla 2020", a.suggestedName)
        assertEquals("Toyota Corolla", CarIdentity.fromModel("Toyota", "Corolla", null).suggestedName)
        assertNotEquals(a.key, CarIdentity.fromModel("Toyota", "Corolla", 2021).key)
    }

    @Test
    fun savedCarsSurviveAReload() {
        val mount = MountCalibration(Vec3(0.1, 0.2, 0.97), Vec3(1.0, 0.0, 0.0))
        val cars = listOf(
            CarProfile("a", "Corolla", "vin:$vin", SpeedInput.OBD, "AA:BB", mount, Peaks(0.3, 0.5, 0.4, 0.6)),
            CarProfile("b", "Truck عربية", null, SpeedInput.ANDROID_AUTO, null, MountCalibration(Vec3(0.0, 0.0, 1.0), null)),
            CarProfile("c", "New"),
        )
        val loaded = CarProfileJson.decode(CarProfileJson.encode(cars, "b"))!!
        assertEquals(cars, loaded.cars)
        assertEquals("b", loaded.activeId)
    }

    @Test
    fun damagedSaveFileIsRejected() {
        assertNull(CarProfileJson.decode("not json"))
        assertNull(CarProfileJson.decode("{}"))
        assertNull(CarProfileJson.decode("""{"cars":[{"id":"a"}]}"""))
        assertNull(CarProfileJson.decode("""{"cars":[{"id":"a","name":"x","input":"BOAT"}]}"""))
    }

    @Test
    fun savedCalibrationSkipsLearningForwardAgain() {
        val first = TelemetryEngine()
        val sim = DriveSimulator(first)
        sim.run(4.0)
        sim.run(8.0, { CarMotion(accel = 2.5) })
        sim.run(5.0, { CarMotion(accel = -2.0) })
        sim.run(8.0, { CarMotion(accel = 2.0) })
        assertEquals(CalibrationState.READY, first.snapshot().calibration)
        val mount = first.mountCalibration()!!
        val saved = first.currentPeaks()

        val second = TelemetryEngine()
        second.loadCar(mount, saved)
        assertEquals(CalibrationState.READY, second.snapshot().calibration)
        assertEquals(saved, second.snapshot().peaks)

        val secondSim = DriveSimulator(second)
        secondSim.run(4.0)
        var error = 0.0
        secondSim.run(3.0, { CarMotion(accel = 2.943) }) { truth ->
            error = abs(second.snapshot().longitudinalG!! - truth.longitudinalG)
        }
        assertTrue("G error with restored calibration: $error", error < 0.05)
    }

    @Test
    fun wrongSavedTiltIsCaughtAtTheNextStop() {
        val engine = TelemetryEngine()
        engine.loadCar(MountCalibration(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)), Peaks())
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        DriveSimulator(engine).run(4.0)
        assertEquals(CalibrationState.LEARNING_FORWARD, engine.snapshot().calibration)
    }

    @Test
    fun wrongSavedForwardCorrectsItselfFromDriving() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        val up = engine.mountCalibration()!!.up
        // Same tilt, but the saved forward direction points 90 degrees off.
        val wrong = (up cross Vec3(1.0, 0.0, 0.0)).normalized()
        val truthForward = DriveSimulator.MOUNT_TILTED.first
        val saved = MountCalibration(up, wrong)
        assertTrue(abs(wrong dot truthForward) < 0.9)
        engine.loadCar(saved, Peaks())

        sim.run(8.0, { CarMotion(accel = 2.5) })
        sim.run(5.0, { CarMotion(accel = -2.0) })
        sim.run(8.0, { CarMotion(accel = 2.0) })
        val learned = engine.mountCalibration()!!.forward!!
        assertTrue("forward should be relearned, dot=${learned dot truthForward}", (learned dot truthForward) > 0.98)
    }
}
