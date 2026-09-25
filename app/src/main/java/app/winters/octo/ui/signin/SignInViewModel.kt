package app.winters.octo.ui.signin

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.normalizeServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {
    var address by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Plain http to an address outside the home network.
    val insecure by derivedStateOf {
        val url = normalizeServerUrl(address)
        url != null && !url.isHttps && !isPrivateHost(url)
    }

    fun submit() {
        if (busy || address.isBlank() || username.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        viewModelScope.launch {
            // Success needs nothing here: the app follows the session state.
            error = sessions.signIn(address, username, password)?.userMessage()
            busy = false
        }
    }
}
