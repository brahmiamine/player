package fr.streamia.tv.logging

import android.content.Context
import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics
import fr.streamia.tv.BuildConfig
import fr.streamia.tv.domain.MediaType
import java.net.URI

object CrashReporter {
    private const val PREFS_NAME = "streamia-crashlytics"
    private const val VERIFY_KEY = "debug-verification-sent-v1"
    private const val MAX_EVENT_LENGTH = 512
    private const val MAX_VALUE_LENGTH = 256

    private fun instance(): FirebaseCrashlytics? =
        runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    fun initialize(context: Context) {
        val crashlytics = instance() ?: return
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("app_version_code", BuildConfig.VERSION_CODE)
        crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        crashlytics.setCustomKey("android_sdk", Build.VERSION.SDK_INT)
        crashlytics.setCustomKey("device_manufacturer", Build.MANUFACTURER.orEmpty().take(MAX_VALUE_LENGTH))
        crashlytics.setCustomKey("device_model", Build.MODEL.orEmpty().take(MAX_VALUE_LENGTH))
        crashlytics.log("app_start")

        if (BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(VERIFY_KEY, false)) {
                crashlytics.recordException(CrashlyticsSetupVerificationException())
                prefs.edit().putBoolean(VERIFY_KEY, true).apply()
            }
        }
    }

    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        val crashlytics = instance() ?: return
        fields.forEach { (key, value) -> setKey(crashlytics, key, value) }
        crashlytics.log(sanitizeFreeText(event).take(MAX_EVENT_LENGTH))
    }

    fun playerAttempt(mediaType: MediaType, rawUrl: String, bufferStarts: Int = 0) {
        log(
            event = "player_attempt",
            fields = mapOf(
                "media_type" to mediaType.name,
                "stream" to sanitizeStreamUrl(rawUrl),
                "buffer_starts" to bufferStarts,
            ),
        )
    }

    fun playerReady(mediaType: MediaType, rawUrl: String, bufferStarts: Int) {
        log(
            event = "player_ready",
            fields = mapOf(
                "media_type" to mediaType.name,
                "stream" to sanitizeStreamUrl(rawUrl),
                "buffer_starts" to bufferStarts,
            ),
        )
    }

    fun recordPlayerError(
        mediaType: MediaType,
        rawUrl: String,
        errorCode: Int,
        errorType: String,
        bufferStarts: Int,
    ) {
        val crashlytics = instance() ?: return
        setKey(crashlytics, "media_type", mediaType.name)
        setKey(crashlytics, "stream", sanitizeStreamUrl(rawUrl))
        setKey(crashlytics, "player_error_code", errorCode)
        setKey(crashlytics, "player_error_type", errorType)
        setKey(crashlytics, "buffer_starts", bufferStarts)
        crashlytics.log("player_error")
        crashlytics.recordException(
            StreamiaPlayerFailureException(
                "Player failure code=$errorCode type=${sanitizeFreeText(errorType)}",
            ),
        )
    }

    fun recordNonFatal(area: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        val crashlytics = instance() ?: return
        setKey(crashlytics, "error_area", sanitizeFreeText(area))
        setKey(crashlytics, "error_type", error::class.java.simpleName)
        fields.forEach { (key, value) -> setKey(crashlytics, key, value) }
        crashlytics.log("non_fatal:${sanitizeFreeText(area)}")
        // Intentionally do not attach the original throwable: network exceptions may contain
        // Xtream URLs with credentials in their message. The controlled exception keeps reports safe.
        crashlytics.recordException(
            StreamiaNonFatalException(
                "${sanitizeFreeText(area)}: ${error::class.java.simpleName}",
            ),
        )
    }

    private fun setKey(crashlytics: FirebaseCrashlytics, rawKey: String, value: Any?) {
        val key = rawKey
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(40)
            .ifBlank { "key" }
        crashlytics.setCustomKey(key, sanitizeFreeText(value?.toString().orEmpty()).take(MAX_VALUE_LENGTH))
    }
}

internal fun sanitizeStreamUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return "stream://empty"
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "stream"
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val kind = segments.firstOrNull { it.equals("live", true) || it.equals("movie", true) || it.equals("series", true) }
            ?.lowercase()
            ?: "stream"
        val tail = segments.lastOrNull()
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(80)
            ?.ifBlank { "***" }
            ?: "***"
        "$scheme://provider$port/$kind/***/***/$tail"
    }.getOrElse {
        "stream://redacted"
    }
}

internal fun sanitizeFreeText(value: String): String = value
    // The trailing '/' is optional: a stream URL that ends right after the password segment
    // (no id/extension after it, e.g. an auth-probe URL "/live/user/pass") has no character
    // left to anchor a mandatory trailing slash on, so requiring one let both credentials
    // through untouched for that shape.
    .replace(Regex("(?i)/(live|movie|series)/[^/\\s?]+/[^/\\s?]+/?")) { match ->
        "/${match.groupValues[1]}/***/***/"
    }
    .replace(Regex("(?i)(username|user|password|pass)=([^&\\s]+)")) { match ->
        "${match.groupValues[1]}=***"
    }
    .replace(Regex("(?i)(token|auth|key)=([^&\\s]+)")) { match ->
        "${match.groupValues[1]}=***"
    }

private class StreamiaPlayerFailureException(message: String) : RuntimeException(message)
private class StreamiaNonFatalException(message: String) : RuntimeException(message)
private class CrashlyticsSetupVerificationException : RuntimeException(
    "Streamia Crashlytics debug verification event",
)
