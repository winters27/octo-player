import Foundation

/// Whether one of Octo's own sign-in asks (the server app start did not sign in to again, or a
/// switch that needs a password) may open its form now: never over a sign-in that is open, and
/// never over a link's form, which the ask would bury. Otherwise the ask shows as a message with
/// Sign in, which asks again.
public func askOpens(hasOpenSignIn: Bool, showingJoin: Bool) -> Bool {
    !hasOpenSignIn && !showingJoin
}
