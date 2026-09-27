package app.winters.octo.cast

import app.winters.octo.output.OutputFinder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// Google Cast as one of the kinds of device music can go to. Everything
// that needs Google's Cast library is in this package, so a build without
// it would leave this module out and keep the rest.
@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds
    @IntoSet
    abstract fun castFinder(finder: CastFinder): OutputFinder
}
