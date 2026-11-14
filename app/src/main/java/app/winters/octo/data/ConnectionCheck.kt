package app.winters.octo.data

import app.winters.octo.connection.ConnectionSecurity
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.letGo
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.ServerInfo
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException

// How long a sign-in check waits for each answer.
private const val CHECK_SECONDS = 30L

// What looking at a server before keeping it found.
sealed interface Checked {
    // It answered at `url`, saying `info`. `creds` are named by the key's
    // owner when signing in with a key; `octo` when its status page answered.
    class Found(val url: HttpUrl, val info: ServerInfo, val creds: Credentials, val extensions: Set<String>, val octo: Boolean) : Checked

    class Refused(val error: SignInError) : Checked
}

// Looks at a server before it is kept: where it answers, the home address's
// certificate, whose key it is, its extensions and whether it is Octo. Every
// call goes through a client of its own, built for these settings with a
// sign-in page cookie jar that starts empty, so a session kept for the
// server in use never lets a new password or another account through, and
// the server in use keeps its own headers meanwhile.
class ConnectionCheck @Inject constructor(
    private val clients: ServerClients,
    private val probe: OctoProbe,
) {
    suspend fun look(main: HttpUrl, settings: ConnectionSettings, creds: Credentials): Checked {
        val (own, security) = clients.forJoin(main, settings, CHECK_SECONDS)
        // The look's own client goes once it is done.
        return try {
            look(own, security, main, settings.home, creds)
        } finally {
            own.letGo()
        }
    }

    private suspend fun look(own: OkHttpClient, security: ConnectionSecurity, main: HttpUrl, home: HttpUrl?, creds: Credentials): Checked {
        val reached = try {
            reachWith(own, main, home, creds)
        } catch (e: SubsonicException) {
            return Checked.Refused(untrusted(security, e, main, home) ?: SignInError.Failed(e))
        }
        // A home address with a certificate the phone does not trust is
        // asked about now, while the user is here to answer.
        if (home != null && reached.url != home) {
            val quick = own.newBuilder().callTimeout(HOME_PING_SECONDS, TimeUnit.SECONDS).build()
            val homeError = runCatching { SubsonicClient(home, creds, quick).ping() }.exceptionOrNull()
            if (homeError is SubsonicException) untrusted(security, homeError, home)?.let { return Checked.Refused(it) }
        }
        val client = SubsonicClient(reached.url, creds, own)
        // With an API key the server says whose it is.
        val named = if (creds.mode == AuthMode.ApiKey) {
            runCatching { client.tokenInfo() }.getOrNull()?.takeIf(String::isNotBlank)?.let { creds.named(it) } ?: creds
        } else {
            creds
        }
        val extensions = runCatching { client.extensions() }.getOrDefault(emptyList())
            .flatMap { ext -> ext.versions.map { "${ext.name}:$it" } }.toSet()
        // The status page is the only Octo signal for now.
        return Checked.Found(reached.url, reached.info, named, extensions, probe.adminReachable(own, reached.url))
    }
}

// Where a server answered, and what it said about itself.
internal class Reached(val url: HttpUrl, val info: ServerInfo)

// Pings the main address. When that cannot connect and there is a home
// address, pings that instead: at home the main address may not loop back.
internal suspend fun reachWith(client: OkHttpClient, main: HttpUrl, home: HttpUrl?, creds: Credentials): Reached = try {
    Reached(main, SubsonicClient(main, creds, client).ping())
} catch (e: SubsonicException.Unreachable) {
    if (home == null || e.cause is SSLException) throw e
    val info = runCatching { SubsonicClient(home, creds, client).ping() }.getOrNull() ?: throw e
    Reached(home, info)
}

// A failure caused by a certificate the phone does not trust, as a
// question for the user, from the security that refused it. Null for any
// other failure.
internal fun untrusted(security: ConnectionSecurity, e: SubsonicException, vararg urls: HttpUrl?): SignInError.Untrusted? {
    if (e !is SubsonicException.Unreachable || e.cause !is SSLException) return null
    for (url in urls.filterNotNull()) {
        val leaf = security.takeRejected(url.host) ?: continue
        return SignInError.Untrusted(url.host, leaf)
    }
    return null
}

// How a failed look at a kept server reads in its row: a refused password,
// a sign-in page in front of it (never taken for a refused password, so
// nothing is signed out), or out of reach.
internal fun reachOf(e: SubsonicException): Reach = when (e) {
    is SubsonicException.WrongCredentials -> Reach.WrongPassword
    is SubsonicException.NotSubsonic -> e.gateway?.let { Reach.SignInPage(it, e.loginSent) } ?: Reach.Unreachable
    else -> Reach.Unreachable
}
