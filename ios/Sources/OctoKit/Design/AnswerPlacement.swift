import Foundation

/// Where a question's two answers go, the main one and the other (Cancel), in points.
public struct AnswerPlacement: Sendable, Equatable {
    /// One over the other, the main one on top, instead of side by side.
    public let stacked: Bool
    /// The main answer's width.
    public let main: Double
    /// The other answer's width.
    public let other: Double

    public init(stacked: Bool, main: Double, other: Double) {
        self.stacked = stacked
        self.main = main
        self.other = other
    }
}

/// Side by side while both answers fit in `room` at their own widths with `gap` between them, the
/// main one widened toward the other's width as far as the room allows, so the main answer is never
/// the narrower one where there is room for that; otherwise stacked, each the room's full width, so
/// neither is cut. A row with no width limit (`room` nil) is side by side, the main one at least the
/// other's width.
public func answerPlacement(main: Double, other: Double, gap: Double, room: Double?) -> AnswerPlacement {
    guard let room else { return AnswerPlacement(stacked: false, main: max(main, other), other: other) }
    guard main + gap + other <= room else { return AnswerPlacement(stacked: true, main: room, other: room) }
    return AnswerPlacement(stacked: false, main: max(main, min(other, room - gap - other)), other: other)
}
