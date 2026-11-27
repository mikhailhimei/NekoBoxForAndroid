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

private const val RELEASE_DOWNLOAD_BASE = "https://github.com/mikhailhimei/NekoBoxForAndroid/releases/download"
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

internal data class AppUpdate(val tag: String, val downloadUrl: String)

internal suspend fun findAppUpdate(
    installedVersion: String,
    lastOffered: String?,
    supportedAbis: Array<String>,
): AppUpdate? {
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
                var update: AppUpdate? = null
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    if (release.optBoolean("draft")) continue
                    val candidate = parseRelease(release.optString("tag_name")) ?: continue
                    if (candidate <= latest) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    val downloadUrl = findApkDownload(assets, candidate.tag, supportedAbis) ?: continue
                    latest = candidate
                    update = AppUpdate(candidate.tag, downloadUrl)
                }
                update
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

private fun findApkDownload(assets: JSONArray, tag: String, supportedAbis: Array<String>): String? {
    for (abi in supportedAbis) {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith("-$abi-debug.apk")) continue
            val url = asset.optString("browser_download_url")
            if (url == "$RELEASE_DOWNLOAD_BASE/$tag/$name") return url
        }
    }
    return null
}
