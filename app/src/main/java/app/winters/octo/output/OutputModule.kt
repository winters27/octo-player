package app.winters.octo.output

import app.winters.octo.output.dlna.RendererFinder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// Media renderers (DLNA) as one of the kinds of device music can go to.
@Module
@InstallIn(SingletonComponent::class)
abstract class OutputModule {
    @Binds
    @IntoSet
    abstract fun rendererFinder(finder: RendererFinder): OutputFinder
}
