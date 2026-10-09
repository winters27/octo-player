package app.winters.octo.ui.family

import app.winters.octo.subsonic.AddToLibrary
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.subsonic.RequestQuality

// What an outside song (one found online) offers this account. Without a
// family, or for an account that adds directly, nothing changes: the add
// reads as it always has. An account that must ask sees its add as Save,
// and asks for a copy separately; one that may only save gets no request.
data class OutsideActions(
    // The add action's words, or null to keep the app's usual ones.
    val addLabel: String?,
    // Whether "Request a copy" is offered.
    val offersRequest: Boolean,
)

val USUAL_OUTSIDE_ACTIONS = OutsideActions(addLabel = null, offersRequest = false)

fun outsideActions(me: FamilyMe?): OutsideActions = when (me?.abilities?.addToLibrary) {
    null, AddToLibrary.Direct -> USUAL_OUTSIDE_ACTIONS
    AddToLibrary.Request -> OutsideActions(addLabel = SAVE, offersRequest = true)
    AddToLibrary.SaveOnly -> OutsideActions(addLabel = SAVE, offersRequest = false)
}

// Whether "Remove from my library" is offered on library songs: for a
// member the family manages, whose own library sits beside the shared one.
// A song of the shared library is refused by the server in plain words,
// since a song does not say which library it is in.
fun offersRemoveFromMyLibrary(me: FamilyMe?): Boolean =
    me != null && me.managed && me.role != FamilyRole.Owner && me.role != FamilyRole.Unmanaged

// Whether this account may keep offline copies. Without a family, always.
fun offlineCopiesAllowed(me: FamilyMe?): Boolean = me?.abilities?.offlineCopies ?: true

// One quality in the request sheet, held back when it is above the plan's.
data class QualityChoice(val quality: RequestQuality, val enabled: Boolean) {
    val label: String get() = qualityLabel(quality)
    val detail: String get() = if (enabled) qualityDetail(quality) else "Not on your plan"
}

// Everything the request sheet shows, from the plan.
data class RequestSheet(
    val choices: List<QualityChoice>,
    // The quality picked when the sheet opens: the best the plan allows.
    val initial: RequestQuality,
    // "7 of 10 requests left this week", or null with no limit.
    val quotaLine: String?,
    // Whether this week's requests are all used. The server still adds a
    // song the family already has, so the sheet says so but still sends.
    val quotaUsed: Boolean,
    // "Your library is full (10.2 of 10 GB)", or null while there is room.
    val storageLine: String?,
    // Whether it goes straight through or waits for a manager.
    val approvalLine: String,
)

// Best first, as a person reads the choices.
private val SHEET_ORDER = listOf(RequestQuality.Best, RequestQuality.Flac, RequestQuality.Mp3)

fun requestSheet(me: FamilyMe): RequestSheet {
    val can = me.abilities
    val max = can.requestQuality
    val choices = SHEET_ORDER.map { QualityChoice(it, it.allowedUnder(max)) }
    val limit = can.weeklyRequestLimit
    val left = (limit - me.requestsThisWeek).coerceAtLeast(0)
    return RequestSheet(
        choices = choices,
        initial = choices.first { it.enabled }.quality,
        quotaLine = when {
            limit <= 0 -> null
            left == 0 -> "You have used all ${plural(limit, "request")} this week. Songs the family already has can still be added."
            else -> "$left of ${plural(limit, "request")} left this week"
        },
        quotaUsed = limit > 0 && left == 0,
        storageLine = if (storageFull(me.storageUsedBytes, can.storageLimitGb)) storageFullLine(me.storageUsedBytes, can.storageLimitGb) else null,
        approvalLine = if (can.autoApprove) "Approved right away" else "Waits for approval",
    )
}
