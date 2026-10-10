package app.winters.octo.di

import app.winters.octo.playback.RealLengths
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    // The real lengths learned by playing songs live in shared core, which
    // knows nothing of Hilt: one for the whole app, which the player fills
    // and every row, the queue and the lyrics read.
    @Provides
    @Singleton
    fun realLengths(): RealLengths = RealLengths()
}
