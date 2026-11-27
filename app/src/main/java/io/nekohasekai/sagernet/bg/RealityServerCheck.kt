package io.nekohasekai.sagernet.bg

import android.net.Network
import android.system.Os
import android.system.OsConstants
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val CHECK_USER_URL = "http://lamaamaama.hopto.org:8000/check-user"
private val userIdSuffix = Regex("""(?:^|-)([PT][0-9]+)$""")
private val checkUserClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

internal fun ProxyEntity.checkUserId(): String? {
    val bean = requireBean() as? VMessBean ?: return null
    if (!bean.isVLESS || bean.realityPubKey.isNullOrBlank()) return null
    return userIdSuffix.find(bean.name.orEmpty().trim())?.groupValues?.get(1)
}

internal suspend fun refreshRealityServerAddress(profile: ProxyEntity, network: Network? = null): Boolean {
    val userId = profile.checkUserId()
    if (userId == null) {
        Logs.i("check-user: skipped; selected profile must be VLESS Reality with a -P<number> or -T<number> name suffix")
        return false
    }
    val bean = profile.requireBean() as VMessBean
    val requestBody = JSONObject().put("userid", userId).toString()
    val request = Request.Builder()
        .url(CHECK_USER_URL)
        .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    val client = network?.let {
        checkUserClient.newBuilder()
            .socketFactory(it.socketFactory)
            .dns(Dns { hostname -> it.getAllByName(hostname).toList() })
            .build()
    } ?: checkUserClient

    Logs.i("check-user: sending POST")
    val address = try {
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

                val body = response.peekBody(129).bytes()
                if (body.size > 128) throw IOException("response is too large")

                val ip = try {
                    (JSONObject(body.toString(Charsets.UTF_8)).opt("ip") as? String)?.trim()
                } catch (e: JSONException) {
                    throw IOException("invalid JSON response", e)
                }
                val parsedIp = ip?.let(::parseIp) ?: throw IOException("invalid or missing ip field")

                ip.takeUnless { parsedIp == parseIp(bean.serverAddress) }
            }
        }
    } catch (e: IOException) {
        throw IOException("check-user: ${e.readableMessage}", e)
    }
    if (address != null) bean.serverAddress = address
    Logs.i("check-user: valid IP received")
    return address != null
}

private fun parseIp(address: String) =
    Os.inet_pton(OsConstants.AF_INET, address) ?: Os.inet_pton(OsConstants.AF_INET6, address)
