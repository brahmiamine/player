package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.CurrentWeather
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.prayerTimes
import fr.streamia.tv.data.weatherEmoji
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// En-tête de l'accueil : horloge, météo et heures de prière.

/**
 * Horloge locale de l'en-tête (heures:minutes:secondes), rafraîchie chaque seconde. L'état vit
 * dans ce composable pour que seule cette zone se recompose, et non toute la liste d'accueil.
 */
@Composable
internal fun LocalClockText(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            // Se recale sur la frontière de la seconde suivante pour éviter la dérive.
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    Text(
        text = now.format(ClockFormatter),
        color = Ink,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Météo de la ville puis les 5 prières du jour ; la prochaine ressort en gras (pas seulement en couleur). */
@Composable
internal fun WeatherAndPrayers(place: HomePlace?, weather: CurrentWeather?, method: PrayerMethod) {
    place ?: return
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = Date()
        }
    }
    val prayers = remember(place, method, now) { prayerTimes(place, method, now) }
    val next = prayers.firstOrNull { (_, time) -> time.after(now) }?.first
    Text(
        buildString {
            weather?.let { append(weatherEmoji(it.weatherCode)).append(' ').append(it.temperatureC).append("°  ") }
            append(place.name.substringBefore(','))
        },
        color = Ink,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        buildAnnotatedString {
            prayers.forEachIndexed { index, (name, time) ->
                if (index > 0) append(" · ")
                val style = if (name == next) SpanStyle(color = Ink, fontWeight = FontWeight.Bold) else SpanStyle(color = MutedInk)
                withStyle(style) { append(name + " " + time.toInstant().atZone(ZoneId.systemDefault()).format(PrayerTimeFormatter)) }
            }
        },
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// DateTimeFormatter + fuseau lu à chaque affichage : un SimpleDateFormat global figeait le fuseau
// de sa création (changement d'heure ou de fuseau de la TV ignoré jusqu'au redémarrage de l'app).
private val PrayerTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
