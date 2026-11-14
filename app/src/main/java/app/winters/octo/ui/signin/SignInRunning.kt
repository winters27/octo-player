package app.winters.octo.ui.signin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

// The sign-in forms open anywhere in the app: running, waiting on a
// certificate question, or holding a received sign-in or a finished sign-up,
// each of which works only once. A link from outside the app, and Octo's own
// sign-in asks, look here before they open a form, so neither ever replaces
// or buries one that is open.
@Singleton
class SignInRunning @Inject constructor() {
    private val _open = MutableStateFlow<Set<SignInViewModel>>(emptySet())
    val open: StateFlow<Set<SignInViewModel>> = _open

    fun mark(form: SignInViewModel, open: Boolean) {
        _open.update { if (open) it + form else it - form }
    }

    // Puts these words under the address of every open form.
    fun noteLink(words: String) {
        _open.value.forEach { it.noteLink(words) }
    }
}
