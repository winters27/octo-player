package app.winters.octo.data

import app.winters.octo.server.PasswordChange
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The signed-in account's change of password, one at a time from any page
// (the Server page's form, Family's sheet). Once it is sent the server may
// take the new password at any moment, so the change runs on its own:
// nothing a page does (a tap outside, Back, the tab tapped again, a link that
// opens another page) cuts it before the phone keeps the new one, and the
// phone and the server never end on different passwords.
@Singleton
class PasswordChanges @Inject constructor(
    private val sessions: SessionRepository,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _changing = MutableStateFlow(false)

    // Whether a change is on its way, from any page.
    val changing: StateFlow<Boolean> = _changing

    // Sends a change, unless one is on its way: then nothing is sent and the
    // answer is false. What it came to, in `words`, goes to the form it came
    // from through `answered`, on the main thread, which answers false once
    // that form is gone; the app's notice then says it. A change that worked
    // is said there either way. A caller that goes away meanwhile leaves the
    // change running to its end.
    suspend fun change(
        current: String,
        new: String,
        family: Boolean,
        words: (PasswordChange) -> String,
        answered: (PasswordChange, String) -> Boolean,
    ): Boolean {
        if (!_changing.compareAndSet(false, true)) return false
        scope.async {
            try {
                val result = sessions.changePassword(current, new, family)
                val said = words(result)
                withContext(Dispatchers.Main) {
                    val shown = answered(result, said)
                    if (result == PasswordChange.Changed) feedback.done(said) else if (!shown) feedback.show(said)
                }
            } finally {
                _changing.value = false
            }
        }.await()
        return true
    }
}
