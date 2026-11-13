import Foundation

/// The sign-in form's fixed word colors, the same as the other apps'. Error words are one red for
/// light themes and one for dark, each reading at 4.5:1 on every page, card, raised and sheet
/// surface of its themes and over every glow they allow.
public let errorWordsLight: UInt32 = 0xFFB4_2318
public let errorWordsDark: UInt32 = 0xFFF9_7C7C

public func errorWordsFor(light: Bool) -> UInt32 { light ? errorWordsLight : errorWordsDark }

extension ResolvedPalette {
    /// An error note's words and glyph in this theme.
    public var errorWords: UInt32 { errorWordsFor(light: light) }
    /// The quiet Optional tag: the accent's words on the accent's tonal fill, roles every theme
    /// already solves.
    public var tagWords: UInt32 { accents.accent }
    public var tagFill: UInt32 { accents.tonal }
}
