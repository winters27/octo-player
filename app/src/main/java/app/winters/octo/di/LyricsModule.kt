package app.winters.octo.di

import app.winters.octo.lyrics.OnlineLyrics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {
    // The online lyrics library lives in shared core, which knows nothing of
    // Hilt, so it is made here: a new one for each user, on the app's client.
    @Provides
    fun onlineLyrics(http: OkHttpClient): OnlineLyrics = OnlineLyrics(http)
}
