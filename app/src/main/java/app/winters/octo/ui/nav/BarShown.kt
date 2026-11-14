package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey

// The pages that draw the sign-in screen.
fun isSignInRoute(route: NavKey?): Boolean =
    route is SignInRoute || route is EditConnectionRoute || route is ServerFormRoute || route is FamilyJoinRoute

// Whether the floating bar and the downloads pill show: not while the full
// player is open, and not over a sign-in page, which has the whole screen.
fun barShown(playerOpen: Boolean, topRoute: NavKey?): Boolean = !playerOpen && !isSignInRoute(topRoute)
