package app.winters.octo.playback

import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest

// Signs in and makes the server the one in use, as the sign-in page asks:
// `keptId` names a kept server signed in to again. Answers what went wrong,
// or the notice to show. ServerSwitch is the one the app uses.
interface SignInSwitch {
    suspend fun signIn(request: SignInRequest, keptId: String? = null): Pair<SignInError?, String?>
}
