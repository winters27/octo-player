package app.winters.octo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// Asks whether the server is Octo by reading its status page. Only answers
// on the home network: the admin pages are not exposed through the tunnel.
class OctoProbe @Inject constructor(http: OkHttpClient) {
    private val quick = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

    suspend fun adminReachable(base: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("api/admin/status").build()
        runCatching {
            quick.newCall(Request.Builder().url(url).build()).execute().use { response ->
                response.isSuccessful &&
                    Json.parseToJsonElement(response.body.string()).jsonObject.containsKey("octo")
            }
        }.getOrDefault(false)
    }
}
