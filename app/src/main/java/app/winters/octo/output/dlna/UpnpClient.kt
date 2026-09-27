package app.winters.octo.output.dlna

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

// The largest description or answer read from a renderer.
private const val MAX_BYTES = 512 * 1024L

// Asks renderers for their descriptions, and sends them requests.
class UpnpClient(private val http: OkHttpClient) {
    // Fetches a description or a list of actions.
    fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.source().let { source ->
                source.request(MAX_BYTES)
                source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
            }
        }
    }

    // Sends `action` to a service and answers its values by name. A
    // refusal throws UpnpError with UPnP's number for why.
    fun call(service: ServiceEndpoint, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val body = soapRequest(service.type, action, args).toByteArray(Charsets.UTF_8).toRequestBody(XML)
        val request = Request.Builder()
            .url(service.controlUrl)
            .header("SOAPACTION", soapAction(service.type, action))
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.source().let { source ->
                source.request(MAX_BYTES)
                source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
            }
            // A refusal comes back as a 500 with the reason inside.
            if (!response.isSuccessful && response.code != 500) throw UpnpError(null, "$action: HTTP ${response.code}")
            return parseSoapResponse(text, action)
        }
    }
}
