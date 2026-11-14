import Foundation

/// The client certificate row's line on the sign-in form: None chosen with no certificate, its name
/// alone while its last valid day is not known, and the name with that day, as `format` writes it,
/// once it is.
public func certificateRowLine(name: String?, expires: Date?, format: (Date) -> String) -> String {
    guard let name else { return SignInWords.certNone }
    guard let expires else { return name }
    return SignInWords.certificateLine(name, date: format(expires))
}
