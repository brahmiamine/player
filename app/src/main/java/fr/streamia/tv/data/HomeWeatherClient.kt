package fr.streamia.tv.data

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Date
import kotlin.math.roundToInt

/** Ville servant à la météo et aux heures de prière de l'en-tête de l'accueil. */
data class HomePlace(val name: String, val latitude: Double, val longitude: Double)

data class CurrentWeather(val temperatureC: Int, val weatherCode: Int)

/**
 * Services gratuits et sans clé (usage personnel) : ipwho.is pour situer la TV d'après sa connexion,
 * Open-Meteo pour la recherche de ville et la météo. Les heures de prière sont calculées hors ligne.
 */
class HomeWeatherClient {

    fun locateByIp(): HomePlace? {
        val json = getJson("https://ipwho.is/?fields=success,city,latitude,longitude")
        if (!json.optBoolean("success")) return null
        return HomePlace(json.optString("city"), json.getDouble("latitude"), json.getDouble("longitude"))
    }

    fun searchCities(query: String): List<HomePlace> {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val results = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=8&language=fr")
            .optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { index ->
            val city = results.getJSONObject(index)
            val details = listOf(city.optString("admin1"), city.optString("country")).filter(String::isNotBlank)
            HomePlace(
                name = (listOf(city.getString("name")) + details).distinct().joinToString(", "),
                latitude = city.getDouble("latitude"),
                longitude = city.getDouble("longitude"),
            )
        }
    }

    fun currentWeather(place: HomePlace): CurrentWeather {
        val current = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,weather_code",
        ).getJSONObject("current")
        return CurrentWeather(current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"))
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Streamia-TV")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Service météo : code $code.")
            JSONObject(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

/** Les 5 prières du jour de [date], dans l'ordre, calculées hors ligne (bibliothèque Adhan). */
fun prayerTimes(place: HomePlace, method: PrayerMethod, date: Date = Date()): List<Pair<String, Date>> {
    val times = PrayerTimes(Coordinates(place.latitude, place.longitude), DateComponents.from(date), method.parameters())
    return listOf("Fajr" to times.fajr, "Dhuhr" to times.dhuhr, "Asr" to times.asr, "Maghrib" to times.maghrib, "Isha" to times.isha)
}

private fun PrayerMethod.parameters(): CalculationParameters = when (this) {
    PrayerMethod.MuslimWorldLeague -> CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
    PrayerMethod.France -> CalculationParameters(12.0, 12.0)
    PrayerMethod.Tunisia -> CalculationParameters(18.0, 18.0)
    PrayerMethod.Egypt -> CalculationMethod.EGYPTIAN.parameters
    PrayerMethod.UmmAlQura -> CalculationMethod.UMM_AL_QURA.parameters
    PrayerMethod.Karachi -> CalculationMethod.KARACHI.parameters
    PrayerMethod.NorthAmerica -> CalculationMethod.NORTH_AMERICA.parameters
}

/** Code météo WMO renvoyé par Open-Meteo → pictogramme. */
fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1, 2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}
