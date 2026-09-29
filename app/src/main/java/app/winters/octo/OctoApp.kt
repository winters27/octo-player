package app.winters.octo

import android.app.Application
import app.winters.octo.data.SessionRepository
import app.winters.octo.device.DeviceArtworkFetcher
import app.winters.octo.device.DeviceArtworkKeyer
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.listening.ListenBrainzSync
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.server.ServerArtworkFetcher
import app.winters.octo.server.ServerArtworkKeyer
import app.winters.octo.server.ServerSync
import app.winters.octo.update.AppUpdates
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class OctoApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var deviceLibrary: DeviceLibrary
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var serverSync: ServerSync
    @Inject lateinit var offline: OfflineDownloads
    @Inject lateinit var listenBrainz: ListenBrainzSync
    @Inject lateinit var updates: AppUpdates

    override fun onCreate() {
        super.onCreate()
        // Keep the library in step with the music on the phone.
        deviceLibrary.start()
        // And with the server's, when one is connected.
        serverSync.start()
        // Downloads left waiting last time carry on.
        offline.start()
        // Plays waiting for ListenBrainz go out.
        listenBrainz.start()
        // New versions of Octo, in the release build only.
        updates.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Covers from a server load through the same connection pool as the API.
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                // Artwork embedded in files on the phone.
                add(DeviceArtworkFetcher.Factory(this@OctoApp))
                add(DeviceArtworkKeyer())
                // Covers on the signed-in server.
                add(ServerArtworkFetcher.Factory(sessions))
                add(ServerArtworkKeyer())
            }
            .crossfade(true)
            .build()
}
