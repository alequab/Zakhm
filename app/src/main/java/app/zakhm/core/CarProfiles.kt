package app.zakhm.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How the phone sits in a car. [forward] is null until it has been learned. */
data class MountCalibration(val up: Vec3, val forward: Vec3?)

/** What a car said about itself. Cars with the same [key] are treated as the same car. */
data class CarIdentity(val key: String, val suggestedName: String) {
    /** The VIN, when that's how this car identified itself. */
    val vin: String? get() = vinOf(key)

    companion object {
        const val VIN_NAME_LENGTH = 6
        private const val VIN_PREFIX = "vin:"

        /** The VIN inside an identity key, if it holds one. */
        fun vinOf(key: String?): String? = key?.takeIf { it.startsWith(VIN_PREFIX) }?.removePrefix(VIN_PREFIX)

        /** Unique to one car. */
        fun fromVin(vin: String, name: String = "Car ${vin.takeLast(VIN_NAME_LENGTH)}") =
            CarIdentity("$VIN_PREFIX$vin", name)

        /** Shared by every car of that model and year, as Android Auto doesn't expose a VIN. */
        fun fromModel(make: String, model: String, year: Int?): CarIdentity {
            val yearText = year?.takeIf { it > 0 }?.toString()
            val key = listOfNotNull(make, model, yearText).joinToString("|") { it.trim().lowercase() }
            return CarIdentity("model:$key", listOfNotNull(make.trim(), model.trim(), yearText).joinToString(" "))
        }
    }
}

data class CarProfile(
    val id: String,
    val name: String,
    val identityKey: String? = null,
    val input: SpeedInput = SpeedInput.PHONE,
    val obdAddress: String? = null,
    val mount: MountCalibration? = null,
    val peaks: Peaks = Peaks(),
)

/** The saved cars and which one is in use. There is always at least one. */
class CarRegistry(
    cars: List<CarProfile>,
    activeId: String?,
    private val newId: () -> String,
) {
    private val saved = cars.toMutableList()
    private var activeCarId: String

    init {
        if (saved.isEmpty()) saved += CarProfile(newId(), DEFAULT_NAME)
        activeCarId = saved.firstOrNull { it.id == activeId }?.id ?: saved.first().id
    }

    val cars: List<CarProfile> get() = saved.toList()
    val active: CarProfile get() = saved.first { it.id == activeCarId }

    fun add(name: String): CarProfile {
        val car = CarProfile(newId(), cleanName(name), input = active.input, obdAddress = active.obdAddress)
        saved += car
        activeCarId = car.id
        return car
    }

    fun select(id: String): Boolean {
        if (saved.none { it.id == id }) return false
        activeCarId = id
        return true
    }

    fun rename(id: String, name: String) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0) saved[i] = saved[i].copy(name = cleanName(name))
    }

    /** Removes a car, except the last one. Returns true if the active car changed. */
    fun delete(id: String): Boolean {
        if (saved.size <= 1 || saved.none { it.id == id }) return false
        saved.removeAll { it.id == id }
        if (activeCarId != id) return false
        activeCarId = saved.first().id
        return true
    }

    fun updateActive(change: (CarProfile) -> CarProfile) {
        val i = saved.indexOfFirst { it.id == activeCarId }
        saved[i] = change(saved[i]).copy(id = activeCarId)
    }

    /** Makes the car saved with [identity] active. Null if no saved car has it, which is the user's call. */
    fun selectKnown(identity: CarIdentity): CarProfile? {
        val car = saved.firstOrNull { it.identityKey == identity.key } ?: return null
        activeCarId = car.id
        return car
    }

    /** Saves [identity] to the active car, replacing any it had. A name the user chose is kept. */
    fun saveToActive(identity: CarIdentity): CarProfile {
        updateActive {
            it.copy(identityKey = identity.key, name = if (it.name == DEFAULT_NAME) identity.suggestedName else it.name)
        }
        return active
    }

    /** Saves [identity] as a new car, which starts from the active car's speed source. */
    fun saveAsNew(identity: CarIdentity): CarProfile {
        val current = active
        val car = CarProfile(
            id = newId(),
            name = identity.suggestedName,
            identityKey = identity.key,
            input = current.input,
            obdAddress = current.obdAddress,
        )
        saved += car
        activeCarId = car.id
        return car
    }

    private fun cleanName(name: String) = name.trim().ifEmpty { DEFAULT_NAME }

    companion object {
        const val DEFAULT_NAME = "My car"
    }
}

/** Reads and writes saved cars. Anything unreadable gives null rather than a half-loaded list. */
object CarProfileJson {
    private const val VERSION = 1

    data class Saved(val cars: List<CarProfile>, val activeId: String?)

    fun encode(cars: List<CarProfile>, activeId: String): String {
        val array = JSONArray()
        cars.forEach { array.put(encodeCar(it)) }
        return JSONObject()
            .put("version", VERSION)
            .put("activeId", activeId)
            .put("cars", array)
            .toString()
    }

    fun decode(text: String): Saved? = try {
        val root = JSONObject(text)
        val array = root.getJSONArray("cars")
        Saved(
            cars = (0 until array.length()).map { decodeCar(array.getJSONObject(it)) },
            activeId = root.optString("activeId").ifEmpty { null },
        )
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun encodeCar(car: CarProfile): JSONObject {
        val json = JSONObject()
            .put("id", car.id)
            .put("name", car.name)
            .put("input", car.input.name)
            .put("peakAccel", car.peaks.accel)
            .put("peakBrake", car.peaks.brake)
            .put("peakLeft", car.peaks.left)
            .put("peakRight", car.peaks.right)
        car.identityKey?.let { json.put("identityKey", it) }
        car.obdAddress?.let { json.put("obdAddress", it) }
        car.mount?.let { mount ->
            json.put("mountUp", encodeVector(mount.up))
            mount.forward?.let { json.put("mountForward", encodeVector(it)) }
        }
        return json
    }

    private fun decodeCar(json: JSONObject) = CarProfile(
        id = json.getString("id"),
        name = json.getString("name"),
        identityKey = json.optString("identityKey").ifEmpty { null },
        input = SpeedInput.valueOf(json.getString("input")),
        obdAddress = json.optString("obdAddress").ifEmpty { null },
        mount = json.optJSONArray("mountUp")?.let { up ->
            MountCalibration(decodeVector(up), json.optJSONArray("mountForward")?.let(::decodeVector))
        },
        peaks = Peaks(
            accel = json.optDouble("peakAccel", 0.0),
            brake = json.optDouble("peakBrake", 0.0),
            left = json.optDouble("peakLeft", 0.0),
            right = json.optDouble("peakRight", 0.0),
        ),
    )

    private fun encodeVector(v: Vec3) = JSONArray().put(v.x).put(v.y).put(v.z)

    private fun decodeVector(a: JSONArray) = Vec3(a.getDouble(0), a.getDouble(1), a.getDouble(2))
}
