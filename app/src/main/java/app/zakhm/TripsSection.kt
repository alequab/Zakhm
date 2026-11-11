package app.zakhm

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zakhm.core.TripSummary
import app.zakhm.core.TripText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The map files a saved drive can be shared as. */
enum class TripFormat(val extension: String, val mime: String) {
    /** Carries its own colours and markers; Google Earth and Google My Maps open it. */
    KML(".kml", "application/vnd.google-earth.kml+xml"),

    /** Plainer, but nearly every map program reads it. */
    GPX(".gpx", "application/gpx+xml"),
}

private const val METERS_PER_KM = 1000.0
private const val METERS_PER_MILE = 1609.344
private const val KMH_PER_MPS = 3.6
private const val MPH_PER_MPS = 2.236936

/** How many drives the settings show before "show all". */
private const val TRIPS_SHOWN = 3

/** When a drive began, in the app's language but with the digits used everywhere else in it. */
internal fun tripTitle(context: Context, summary: TripSummary): String {
    val language = context.resources.configuration.locales[0].language
    val locale = Locale.forLanguageTag("$language-u-nu-latn")
    return SimpleDateFormat("EEE d MMM yyyy, HH:mm", locale).format(Date(summary.startMs))
}

private class TripFigures(val duration: String, val distance: String, val topSpeed: String, val strongestG: String)

private fun tripFigures(context: Context, summary: TripSummary, useMph: Boolean): TripFigures {
    val minutes = summary.durationS / 60
    val duration = if (minutes < 60) {
        context.getString(R.string.drive_minutes, minutes)
    } else {
        context.getString(R.string.drive_hours, minutes / 60, minutes % 60)
    }
    val distance = "%.1f %s".format(
        Locale.US,
        summary.distanceM / if (useMph) METERS_PER_MILE else METERS_PER_KM,
        context.getString(if (useMph) R.string.unit_mi else R.string.unit_km),
    )
    val speed = "%.0f %s".format(
        Locale.US,
        summary.topSpeedMps * if (useMph) MPH_PER_MPS else KMH_PER_MPS,
        context.getString(if (useMph) R.string.unit_mph else R.string.unit_kmh),
    )
    return TripFigures(duration, distance, speed, "%.2f".format(Locale.US, summary.strongestG))
}

/** A drive in one line: how long, how far, how fast and how hard. */
internal fun tripDetails(context: Context, summary: TripSummary, useMph: Boolean): String {
    val figures = tripFigures(context, summary, useMph)
    return context.getString(R.string.drive_details, figures.duration, figures.distance, figures.topSpeed, figures.strongestG)
}

/** The words written into a drive's map file. */
internal fun tripText(context: Context, summary: TripSummary, useMph: Boolean): TripText {
    val figures = tripFigures(context, summary, useMph)
    return TripText(
        facts = listOf(
            context.getString(R.string.trip_fact_date) to tripTitle(context, summary),
            context.getString(R.string.trip_fact_duration) to figures.duration,
            context.getString(R.string.trip_fact_distance) to figures.distance,
            context.getString(R.string.trip_top_speed) to figures.topSpeed,
            context.getString(R.string.trip_fact_strongest_g) to "${figures.strongestG} G",
        ),
        speedLabel = context.getString(R.string.trip_speed_folder),
        timeLabel = context.getString(R.string.trip_fact_time),
        gLabel = context.getString(R.string.trip_g_folder),
        forwardGLabel = context.getString(R.string.trip_forward_g),
        sideGLabel = context.getString(R.string.trip_side_g),
        driveFolder = context.getString(R.string.trip_drive_folder),
        tourName = context.getString(R.string.trip_tour),
        timelineName = context.getString(R.string.trip_timeline),
        rightToLeft = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL,
    name = context.getString(R.string.trip_file_name, tripTitle(context, summary)),
    description = tripDetails(context, summary, useMph),
    speedFolder = context.getString(R.string.trip_speed_folder),
    gFolder = context.getString(R.string.trip_g_folder),
    start = context.getString(R.string.trip_start),
    end = context.getString(R.string.trip_end),
    topSpeed = context.getString(R.string.trip_top_speed),
    speedingUp = context.getString(R.string.trip_speeding_up),
    braking = context.getString(R.string.trip_braking),
    leftTurn = context.getString(R.string.trip_left),
    rightTurn = context.getString(R.string.trip_right),
    speedUnit = context.getString(if (useMph) R.string.unit_mph else R.string.unit_kmh),
    speedFactor = if (useMph) MPH_PER_MPS else KMH_PER_MPS,
    )
}

@Composable
internal fun TripsSection(
    trips: List<SavedTrip>,
    useMph: Boolean,
    onShare: (SavedTrip, TripFormat) -> Unit,
    onDelete: (SavedTrip) -> Unit,
) {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedTrip?>(null) }

    SectionTitle(R.string.section_drives)
    Hint(stringResource(R.string.drives_hint))
    if (trips.isEmpty()) Hint(stringResource(R.string.drives_none))

    (if (showAll) trips else trips.take(TRIPS_SHOWN)).forEach { trip ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tripTitle(context, trip.summary), color = Primary, fontSize = DashType.Body, fontWeight = FontWeight.SemiBold)
            Text(tripDetails(context, trip.summary, useMph), color = Secondary, fontSize = DashType.Body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(stringResource(R.string.drive_kml), { onShare(trip, TripFormat.KML) }, Modifier.weight(1f))
                CompactButton(stringResource(R.string.drive_gpx), { onShare(trip, TripFormat.GPX) }, Modifier.weight(1f))
                CompactButton(stringResource(R.string.car_delete), { deleting = trip }, Modifier.weight(1f))
            }
        }
    }
    if (!showAll && trips.size > TRIPS_SHOWN) {
        CompactButton(stringResource(R.string.drives_show_all, trips.size), { showAll = true }, Modifier.fillMaxWidth())
    }

    deleting?.let { trip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.drive_delete_title)) },
            text = { Text(stringResource(R.string.drive_delete_body, tripTitle(context, trip.summary))) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(trip)
                    deleting = null
                }) { Text(stringResource(R.string.car_delete), color = Alert) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
