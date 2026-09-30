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

// Whether the server is Octo: its status page answered at sign-in, it calls
// itself Octo, or it lists an Octo extension. The status page only answers
// at home, so away from home the extensions are what tell.
fun isOctoServer(probed: Boolean, serverType: String?, extensions: Set<String>): Boolean =
    probed || serverType.equals("octo", ignoreCase = true) || extensions.any { it.startsWith("octo") }

val Session.runsOcto: Boolean get() = isOctoServer(isOcto, serverType, extensions)

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
