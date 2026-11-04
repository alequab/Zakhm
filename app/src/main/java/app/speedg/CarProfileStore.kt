package app.speedg

import android.content.Context
import android.util.AtomicFile
import app.speedg.core.CarProfile
import app.speedg.core.CarProfileJson
import java.io.File
import java.io.IOException

/** Keeps saved cars in the app's private storage. VINs in here never leave the phone. */
class CarProfileStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cars.json"))

    fun load(): CarProfileJson.Saved? = try {
        CarProfileJson.decode(file.readFully().toString(Charsets.UTF_8))
    } catch (_: IOException) {
        null
    }

    fun save(cars: List<CarProfile>, activeId: String) {
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            stream.write(CarProfileJson.encode(cars, activeId).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
        }
    }
}
