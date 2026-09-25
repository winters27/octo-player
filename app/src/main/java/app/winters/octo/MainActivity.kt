package app.winters.octo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.winters.octo.design.OctoTheme
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.ui.nav.MainShell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var library: DeviceLibrary

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app opens on the library; servers are an optional add-on.
        setContent { OctoTheme { MainShell(library) } }
    }
}
