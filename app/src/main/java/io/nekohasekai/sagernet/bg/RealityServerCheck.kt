package io.nekohasekai.sagernet.bg

import android.net.Network
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.parseNumericAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

private const val CHECK_USER_URL = "http://lamaamaama.hopto.org:8000/check-user/vless"
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

internal suspend fun refreshRealityProfile(profile: ProxyEntity, network: Network? = null): Boolean {
    val userId = profile.checkUserId()
    if (userId == null) {
        Logs.i("check-user: skipped; selected profile must be VLESS Reality with a -P<number> or -T<number> name suffix")
        return false
    }
    val bean = profile.requireBean() as VMessBean
    val requestBody = JSONObject()
        .put("userid", userId)
        .put("login", bean.uuid)
        .toString()
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
    val refreshed = try {
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

                val body = response.peekBody(8193).bytes()
                if (body.size > 8192) throw IOException("response is too large")

                val payload = try {
                    JSONObject(body.toString(Charsets.UTF_8))
                } catch (e: JSONException) {
                    throw IOException("invalid JSON response", e)
                }
                val serverIp = (payload.opt("ip") as? String)?.trim()?.parseNumericAddress()
                    ?: throw IOException("invalid or missing ip field")
                if (serverIp == bean.serverAddress.parseNumericAddress()) {
                    return@withContext null
                }

                val link = (payload.opt("vless") as? String)?.trim()
                if (link == null || !link.startsWith("vless://")) {
                    throw IOException("invalid or missing vless field")
                }
                val parsed = try {
                    val url = ("https://" + link.removePrefix("vless://")).toHttpUrl()
                    VMessBean().apply {
                        alterId = -1
                        parseDuckSoft(url)
                    }
                } catch (_: Exception) {
                    null
                }
                if (parsed == null || !parsed.isVLESS || parsed.uuid.isNullOrBlank() ||
                    parsed.realityPubKey.isNullOrBlank()
                ) {
                    throw IOException("invalid VLESS Reality link")
                }
                val returnedId = userIdSuffix.find(parsed.name.orEmpty().trim())?.groupValues?.get(1)
                if (returnedId != null && returnedId != userId) {
                    throw IOException("VLESS link belongs to another user")
                }
                parsed.apply {
                    name = bean.name
                    customOutboundJson = bean.customOutboundJson
                    customConfigJson = bean.customConfigJson
                    enableMux = bean.enableMux
                    muxPadding = bean.muxPadding
                    muxType = bean.muxType
                    muxConcurrency = bean.muxConcurrency
                    initializeDefaultValues()
                }
            }
        }
    } catch (e: IOException) {
        throw IOException("check-user: ${e.readableMessage}", e)
    } ?: return false
    if (refreshed == bean) return false
    withContext(Dispatchers.IO) {
        SagerDatabase.instance.runInTransaction(Callable {
            val current = SagerDatabase.proxyDao.getById(profile.id)
            val currentBean = current?.requireBean()
            if (current == null || currentBean != bean || currentBean.name != bean.name) {
                throw IOException("check-user: profile changed during refresh")
            }
            SagerDatabase.proxyDao.updateProxy(current.putBean(refreshed))
        })
    }
    profile.putBean(refreshed)
    Logs.i("check-user: VLESS profile updated")
    return true
}
