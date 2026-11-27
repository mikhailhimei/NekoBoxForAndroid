package io.nekohasekai.sagernet.bg

import android.system.Os
import android.system.OsConstants
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val CHECK_USER_URL = "http://lamaamaama.hopto.org/check-user"
private val userIdSuffix = Regex("""(?:^|-)(P[0-9]+)$""")
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

internal suspend fun refreshRealityServerAddress(profile: ProxyEntity) {
    val userId = profile.checkUserId() ?: return
    val bean = profile.requireBean() as VMessBean
    val request = Request.Builder()
        .url(CHECK_USER_URL)
        .post(userId.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        .build()

    val address = withContext(Dispatchers.IO) {
        checkUserClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("check-user: HTTP ${response.code}")
            }
            val body = response.peekBody(129).bytes()
            val ip = body.toString(Charsets.UTF_8).trim()
            val parsedIp = parseIp(ip)
            if (body.size > 128 || parsedIp == null) {
                throw IOException("check-user: invalid IP address")
            }
            ip.takeUnless { parsedIp == parseIp(bean.serverAddress) }
        }
    }
    if (address != null) bean.serverAddress = address
}

private fun parseIp(address: String) =
    Os.inet_pton(OsConstants.AF_INET, address) ?: Os.inet_pton(OsConstants.AF_INET6, address)
