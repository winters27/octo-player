package app.winters.octo

import android.app.Application
import app.winters.octo.device.DeviceArtworkFetcher
import app.winters.octo.device.DeviceArtworkKeyer
import app.winters.octo.device.DeviceLibrary
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

    override fun onCreate() {
        super.onCreate()
        // Keep the library in step with the music on the phone.
        deviceLibrary.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Covers from a server load through the same connection pool as the API.
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                // Artwork embedded in files on the phone.
                add(DeviceArtworkFetcher.Factory(this@OctoApp))
                add(DeviceArtworkKeyer())
            }
            .crossfade(true)
            .build()
}
