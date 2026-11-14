package app.winters.octo.ui.signin

import androidx.lifecycle.ViewModel
import app.winters.octo.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// For the app's shell, which opens a kept server's sign-in form when the
// sessions ask for it (SessionRepository.signInAsk), and which looks at the
// sign-in forms open anywhere before it opens a family link or an ask.
@HiltViewModel
class SignInShellViewModel @Inject constructor(val sessions: SessionRepository, val running: SignInRunning) : ViewModel()
