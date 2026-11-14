package app.winters.octo.di

import app.winters.octo.connection.JoinClients
import app.winters.octo.connection.ServerClients
import app.winters.octo.playback.ServerSwitch
import app.winters.octo.playback.SignInSwitch
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// What the sign-in page signs in and joins through: the app's own switch
// and clients.
@Module
@InstallIn(SingletonComponent::class)
abstract class SignInModule {
    @Binds
    abstract fun signIns(switcher: ServerSwitch): SignInSwitch

    @Binds
    abstract fun joinClients(clients: ServerClients): JoinClients
}
