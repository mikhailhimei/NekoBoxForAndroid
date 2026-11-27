package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val APP_UPDATE_APK_URL = "http://lamaamaama.hopto.org:8000/NekoBox.apk"
private const val RELEASES_URL =
    "https://api.github.com/repos/mikhailhimei/NekoBoxForAndroid/releases?per_page=20"
private const val MAX_RELEASES_BYTES = 512L * 1024
private val releaseTag = Regex("""v?([0-9]+)\.([0-9]+)\.([0-9]+)-check-user\.([0-9]+)""")
private val updateClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

private data class AppRelease(
    val tag: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<AppRelease> {
    override fun compareTo(other: AppRelease) =
        compareValuesBy(this, other, AppRelease::major, AppRelease::minor, AppRelease::patch, AppRelease::build)
}

private fun parseRelease(tag: String): AppRelease? {
    val match = releaseTag.matchEntire(tag) ?: return null
    val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    return AppRelease(tag, parts[0], parts[1], parts[2], parts[3])
}

internal suspend fun findAppUpdate(installedVersion: String, lastOffered: String?): String? {
    val installed = parseRelease(installedVersion) ?: return null
    val lastOfferedRelease = lastOffered?.let(::parseRelease)
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            updateClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.peekBody(MAX_RELEASES_BYTES + 1).bytes()
                if (bytes.size > MAX_RELEASES_BYTES) throw IOException("response is too large")

                val releases = JSONArray(bytes.toString(Charsets.UTF_8))
                var latest = maxOf(installed, lastOfferedRelease ?: installed)
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    if (release.optBoolean("draft")) continue
                    val candidate = parseRelease(release.optString("tag_name")) ?: continue
                    if (candidate > latest) latest = candidate
                }
                latest.tag.takeIf { latest > installed && (lastOfferedRelease == null || latest > lastOfferedRelease) }
            }
        } catch (e: IOException) {
            Logs.i("App update check failed", e)
            null
        } catch (e: JSONException) {
            Logs.i("Invalid GitHub release data", e)
            null
        }
    }
}
