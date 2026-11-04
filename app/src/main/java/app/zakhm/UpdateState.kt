package app.zakhm

import app.zakhm.core.AvailableUpdate

/** Where an update stands, for the banner on the main screen. */
sealed class UpdateState(val update: AvailableUpdate?) {
    data object None : UpdateState(null)
    class Available(update: AvailableUpdate) : UpdateState(update)
    class NeedsPermission(update: AvailableUpdate) : UpdateState(update)
    class Downloading(update: AvailableUpdate, val percent: Int) : UpdateState(update)
    class Installing(update: AvailableUpdate) : UpdateState(update)
    class Failed(update: AvailableUpdate) : UpdateState(update)
}
