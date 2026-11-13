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
import app.zakhm.core.SpeedInput
import app.zakhm.core.TripSummary
import app.zakhm.core.TripText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The map files a saved drive can be shared as. */
enum class TripFormat(val extension: String, val mime: String) {
    /** KML packed with its pictures: its own colours, markers and legend. Google Earth and Google My Maps open it. */
    KMZ(".kmz", "application/vnd.google-earth.kmz"),

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

private class TripFigures(val duration: String, val distance: String, val topSpeed: String, val strongestG: String, val moving: String)

private fun tripFigures(context: Context, summary: TripSummary, useMph: Boolean): TripFigures {
    fun span(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes < 60) {
            context.getString(R.string.drive_minutes, minutes)
        } else {
            context.getString(R.string.drive_hours, minutes / 60, minutes % 60)
        }
    }
    val duration = span(summary.durationS)
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
    return TripFigures(duration, distance, speed, "%.2f".format(Locale.US, summary.strongestG), span(summary.movingS))
}

/** A drive in one line: how long, how far, how fast and how hard. */
internal fun tripDetails(context: Context, summary: TripSummary, useMph: Boolean): String {
    val figures = tripFigures(context, summary, useMph)
    return context.getString(R.string.drive_details, figures.duration, figures.distance, figures.topSpeed, figures.strongestG)
}

/** The words written into a drive's map file. */
internal fun tripText(context: Context, summary: TripSummary, useMph: Boolean): TripText {
    val figures = tripFigures(context, summary, useMph)
    val speedFactor = if (useMph) MPH_PER_MPS else KMH_PER_MPS
    val speedUnit = context.getString(if (useMph) R.string.unit_mph else R.string.unit_kmh)
    val metre = context.getString(R.string.unit_m)
    // Average over the time spent moving, so a long wait at the lights doesn't drag it down.
    val average = summary.movingS.takeIf { it > 0 }?.let { "%.0f %s".format(Locale.US, summary.distanceM / it * speedFactor, speedUnit) }
    return TripText(
        facts = listOfNotNull(
            context.getString(R.string.trip_fact_date) to tripTitle(context, summary),
            context.getString(R.string.trip_fact_duration) to figures.duration,
            context.getString(R.string.trip_fact_moving) to figures.moving,
            context.getString(R.string.trip_fact_distance) to figures.distance,
            average?.let { context.getString(R.string.trip_fact_average) to it },
            context.getString(R.string.trip_top_speed) to figures.topSpeed,
            context.getString(R.string.trip_fact_strongest_g) to "${figures.strongestG} G",
            context.getString(R.string.trip_fact_total_g) to "%.2f G".format(Locale.US, summary.strongestCombinedG),
            summary.climbM?.let { context.getString(R.string.trip_fact_climb) to "%.0f %s".format(Locale.US, it, metre) },
            summary.carSpeedShare?.let { context.getString(R.string.trip_fact_car_speed) to "%.0f%%".format(Locale.US, it * 100) },
        ),
        totalGLabel = context.getString(R.string.trip_total_g),
        stopsFolder = context.getString(R.string.trip_stops),
        stopped = context.getString(R.string.trip_stopped),
        durationLabel = context.getString(R.string.trip_fact_duration),
        launchesFolder = context.getString(R.string.trip_launches),
        // Round figures in the unit the driver reads.
        launchTargetsMps = (if (useMph) listOf(30.0, 45.0, 60.0) else listOf(60.0, 80.0, 100.0)).map { it / speedFactor },
        speedBefore = context.getString(R.string.trip_speed_before),
        speedAfter = context.getString(R.string.trip_speed_after),
        brakingDistance = context.getString(R.string.trip_braking_distance),
        postsFolder = context.getString(R.string.trip_posts),
        distanceLabel = context.getString(R.string.trip_fact_distance),
        averageSpeed = context.getString(R.string.trip_average_so_far),
        distanceUnit = context.getString(if (useMph) R.string.unit_mi else R.string.unit_km),
        metresPerDistanceUnit = if (useMph) METERS_PER_MILE else METERS_PER_KM,
        metreUnit = metre,
        secondUnit = context.getString(R.string.unit_s),
        gLayerName = context.getString(R.string.trip_g_layer),
        gapsFolder = context.getString(R.string.trip_gaps),
        gapName = context.getString(R.string.trip_gap),
        arrowsFolder = context.getString(R.string.trip_arrows),
        weakGpsName = context.getString(R.string.trip_weak_gps),
        accuracyLabel = context.getString(R.string.trip_accuracy),
        heightLabel = context.getString(R.string.trip_height),
        speedFromLabel = context.getString(R.string.trip_speed_from),
        speedSources = mapOf(
            SpeedInput.PHONE to context.getString(R.string.trip_source_phone),
            SpeedInput.OBD to context.getString(R.string.trip_source_obd),
            SpeedInput.ANDROID_AUTO to context.getString(R.string.trip_source_auto),
        ),
        legendName = context.getString(R.string.trip_legend),
        duration = { s ->
            if (s < 60) context.getString(R.string.trip_seconds, s.toInt()) else context.getString(R.string.trip_minutes_seconds, (s / 60).toInt(), (s % 60).toInt())
        },
        speedLabel = context.getString(R.string.trip_speed_folder),
        timeLabel = context.getString(R.string.trip_fact_time),
        gLabel = context.getString(R.string.trip_g_folder),
        forwardGLabel = context.getString(R.string.trip_forward_g),
        sideGLabel = context.getString(R.string.trip_side_g),
        driveFolder = context.getString(R.string.trip_drive_folder),
        tourName = context.getString(R.string.trip_tour),
        timelineName = context.getString(R.string.trip_timeline),
        wallName = context.getString(R.string.trip_wall),
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
    speedUnit = speedUnit,
    speedFactor = speedFactor,
    )
}

@Composable
internal fun TripsSection(
    trips: List<SavedTrip>,
    useMph: Boolean,
    onShare: (SavedTrip, TripFormat) -> Unit,
    onDelete: (SavedTrip) -> Unit,
    onOpen: (SavedTrip) -> Unit = {},
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
            CompactButton(stringResource(R.string.drive_open), { onOpen(trip) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(stringResource(R.string.drive_share, stringResource(R.string.drive_kmz)), { onShare(trip, TripFormat.KMZ) }, Modifier.weight(1f))
                CompactButton(stringResource(R.string.drive_share, stringResource(R.string.drive_gpx)), { onShare(trip, TripFormat.GPX) }, Modifier.weight(1f))
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
