package app.winters.octo.connection

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

// A client of its own for a family join or hand-over, with its security,
// which keeps a certificate it refused, to ask about. ServerClients is the
// one the app uses.
interface JoinClients {
    fun forJoin(main: HttpUrl, settings: ConnectionSettings, seconds: Long = 20): Pair<OkHttpClient, ConnectionSecurity>
}
