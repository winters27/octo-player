package app.winters.octo.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTheme
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// A pretend Octo server with Family on, on the phone itself, answering
// calls by the last part of their path.
class FamilyPhoneServer : AutoCloseable {
    private val server = MockWebServer()
    private val answers = ConcurrentHashMap<String, () -> String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = answers[request.url.pathSegments.lastOrNull().orEmpty()]?.invoke() ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
        answer("getFamilySignInPending") { ok("") }
    }

    val address: String get() = server.url("/").toString().removeSuffix("/")

    fun answer(endpoint: String, body: () -> String) {
        answers[endpoint] = body
    }

    // A Subsonic answer, ok, with this payload.
    fun ok(payload: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true${if (payload.isEmpty()) "" else ",$payload"}}}"""

    // A sign-in code that lasts a minute and forty seconds, with both addresses or only the home one.
    fun answerStart(anywhere: Boolean = true) = answer("startFamilySignIn") {
        val page = if (anywhere) "\"https://music.example.com/family/signin\"" else "null"
        val outside = if (anywhere) "\"https://music.example.com\"" else "null"
        ok(
            """"familySignIn":{"token":"tok_1","expires":"${Instant.now().plusSeconds(100)}","links":{"anywhere":$page,"home":"http://192.168.1.20:4533/family/signin"},""" +
                """"servers":{"anywhere":$outside,"home":"http://192.168.1.20:4533"},"anywhereAvailable":$anywhere}""",
        )
    }

    fun model(): FamilyModel = FamilyModel(
        client = { SubsonicClient(server.url("/"), Credentials("alex", "secret"), OkHttpClient()) },
        supported = { true },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    override fun close() = server.close()
}

// The family sheets as the Family screen hosts them: the real sheet hosts,
// full screen over the app's background, at a text size and, for a narrower
// phone, a width.
@Composable
fun FamilySheetsAsShipped(model: FamilyModel, fontScale: Float = 1f, widthDp: Int? = null) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        OctoTheme {
            Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                Box(if (widthDp != null) Modifier.width(widthDp.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    HandOverSheetHost(model, "https://music.example.com", "alex")
                    InviteSheetHost(model, "https://music.example.com")
                }
            }
        }
    }
}
