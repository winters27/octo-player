package app.winters.octo.ui.signin

import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.testDevices
import app.winters.octo.data.installTestKeystore
import app.winters.octo.data.testSessions

// The sign-in view model as the app makes it, for the page's composed rows:
// its sessions in memory, the switch never built (no row reaches it, and a
// call into it fails), and the app's own join clients and devices.
internal fun signInForm(): SignInViewModel {
    installTestKeystore()
    return SignInViewModel(testSessions(), notBuilt(), ServerClients(ApplicationProvider.getApplicationContext(), testDevices()), testDevices())
}

// An object made without running its constructor.
internal inline fun <reified T> notBuilt(): T {
    val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
}
