package app.winters.octo.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.connection.CertificateChange
import app.winters.octo.connection.ConnectionSecurity
import app.winters.octo.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// The question asked again, from anywhere in the app, when a host of the
// server in use shows another certificate than the one the user trusted
// there (a self-made or private one, renewed). Trusting it keeps its pin
// with the server, so the next request goes through; Cancel leaves the
// server out of reach and that certificate is not asked about again.
@HiltViewModel
class CertificateChangeViewModel @Inject constructor(
    private val security: ConnectionSecurity,
    private val sessions: SessionRepository,
) : ViewModel() {
    val asking: StateFlow<CertificateChange?> = security.certificateChange

    fun trust(change: CertificateChange) {
        viewModelScope.launch {
            sessions.trustChangedCertificate(change.host, change.pin)
            security.answered(change, trusted = true)
        }
    }

    fun cancel(change: CertificateChange) = security.answered(change, trusted = false)
}

// The same question sign-in asks, saying the certificate changed.
@Composable
fun CertificateChangeHost(owner: CertificateChangeViewModel = hiltViewModel()) {
    val asked by owner.asking.collectAsStateWithLifecycle()
    TrustSheet(
        asked?.let { TrustAsk(it.host, it.fingerprint) },
        onTrust = { asked?.let(owner::trust) },
        onCancel = { asked?.let(owner::cancel) },
        changed = true,
    )
}
