package app.winters.octo.ui.signin

import androidx.lifecycle.ViewModel
import app.winters.octo.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// For the app's shell, which opens a kept server's sign-in form when the
// sessions ask for it (SessionRepository.signInAsk).
@HiltViewModel
class SignInShellViewModel @Inject constructor(val sessions: SessionRepository) : ViewModel()
