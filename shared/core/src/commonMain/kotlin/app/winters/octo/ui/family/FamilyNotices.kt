package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import kotlinx.serialization.Serializable

// One notice to show: a request of mine was decided or arrived, or (for a
// manager) requests are waiting. `key` stays the same for the same news,
// so showing it twice replaces rather than adds.
data class FamilyNotice(val key: String, val title: String, val text: String)

// What has been told already: each request's state when it was last told,
// and how many waiting requests a manager was last told about. Kept by the
// app between checks, so each piece of news is told once.
@Serializable
data class NoticeMemory(
    val told: Map<String, String> = emptyMap(),
    val waitingTold: Int = 0,
    // False until the first check: what is already decided then is old news.
    val started: Boolean = false,
)

// The states worth a notice.
private val TOLD = setOf(FamilyRequestState.Approved, FamilyRequestState.Declined, FamilyRequestState.Done, FamilyRequestState.Failed)

const val WAITING_NOTICE_KEY = "family-waiting"

// The notices for a check, and what to remember after it. `mine` are the
// account's own requests; `waiting` is the family's waiting count for a
// manager, or null for anyone else. The first check tells nothing about
// requests, only a manager's waiting count.
fun familyNotices(memory: NoticeMemory, mine: List<FamilyRequest>, waiting: Int?): Pair<List<FamilyNotice>, NoticeMemory> {
    val notices = mutableListOf<FamilyNotice>()
    if (memory.started) {
        for (request in mine) {
            if (request.state !in TOLD || memory.told[request.id] == request.state.name) continue
            notices += requestNotice(request)
        }
    }
    val count = waiting ?: 0
    if (count > 0 && count > memory.waitingTold) {
        notices += FamilyNotice(WAITING_NOTICE_KEY, "${plural(count, "request")} waiting", "Open Family to approve or decline.")
    }
    val remembered = NoticeMemory(
        told = mine.associate { it.id to it.state.name },
        waitingTold = count,
        started = true,
    )
    return notices to remembered
}

// A request answered while the app watched (the request sheet showed its
// answer), so the next check does not tell it again.
fun NoticeMemory.alreadyTold(request: FamilyRequest): NoticeMemory = copy(told = told + (request.id to request.state.name))

fun requestNotice(request: FamilyRequest): FamilyNotice {
    val what = requestTitle(request)
    return when (request.state) {
        FamilyRequestState.Approved -> FamilyNotice(request.id, "Approved", "$what is on its way to your library.")
        FamilyRequestState.Declined -> FamilyNotice(
            request.id,
            "Declined",
            request.note.takeIf(String::isNotBlank)?.let { "$what. \"$it\"" } ?: what,
        )
        FamilyRequestState.Done -> FamilyNotice(request.id, "Added", "$what. ${outcomeLine(request.outcome)}.")
        FamilyRequestState.Failed -> FamilyNotice(request.id, "Could not get it", "$what. ${request.failure?.takeIf(String::isNotBlank) ?: "Ask for it again later."}")
        else -> FamilyNotice(request.id, "Request", what)
    }
}
