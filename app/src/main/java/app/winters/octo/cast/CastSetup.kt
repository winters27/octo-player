package app.winters.octo.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

// How Octo uses Google Cast, read by the Cast framework from the manifest:
// the standard receiver every Cast device has (no receiver app of our
// own), and no notification or media session from the framework, since
// Octo's own session already covers the notification and lock screen.
// A session is never picked back up after the app restarts: the phone
// drives the queue, so a cast without the app would stop after one song.
class CastSetup : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build(),
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
