package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyLinkChoices
import java.time.Instant

// The words and rules of the family popups and sheets (an invite, a
// sign-in handed to another device, and the Your login card), the same on
// the phone and the desktop.

// A new member's invite popup, or the new sign-in link after a manager
// resets a member's password: who it is for, their link once made, and how
// asking for a new one goes.
data class InviteSheet(
    val name: String,
    val username: String,
    val url: String?,
    val loading: Boolean = false,
    val error: String? = null,
    // The invite's two links, when the server gives them.
    val links: FamilyLinkChoices? = null,
    val anywhereAvailable: Boolean = true,
    // The server has no public address: the link works only at home.
    val homeOnly: Boolean = false,
    // Whether they may listen away from home.
    val awayAllowed: Boolean = true,
    // A reset: the member had a password, which no longer works.
    val reset: Boolean = false,
) {
    val title: String get() = if (reset) "New sign-in link for $name" else "Invite $name"

    val options: LinkOptions get() = inviteLinkOptions(url, links, anywhereAvailable, homeOnly, awayAllowed)

    override fun toString() = "InviteSheet(name=$name, loading=$loading, reset=$reset)"
}

// When `expires` (an ISO time) falls, in milliseconds, or null without one.
fun expiresAtMs(expires: String?): Long? = expires?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

// Seconds left until `expires` (an ISO time), or null when the server set
// no expiry.
fun secondsLeft(expires: String?, now: Instant): Long? {
    val at = expires?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return (at.epochSecond - now.epochSecond).coerceAtLeast(0)
}

// "9:42": minutes and seconds.
fun minutesSeconds(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

// The header's line under the title: "Works once · expires in 1:42", or
// without an expiry "Works once". At the end a new code is on its way.
fun countdownLine(seconds: Long?): String = when {
    seconds == null -> "Works once"
    seconds <= 0 -> "Works once · getting a new code"
    else -> "Works once · expires in ${minutesSeconds(seconds)}"
}

// Under 30 seconds the countdown shows in the warning color.
fun countdownWarns(seconds: Long?): Boolean = seconds != null && seconds < 30

// What a screen reader is told about the countdown: only near the end,
// so it is not read every second.
fun countdownAnnouncement(seconds: Long?): String? = when {
    seconds == null -> null
    seconds <= 0 -> "This code expired. Getting a new one."
    seconds <= 30 -> "Thirty seconds left"
    else -> null
}

// A long text with its middle left out, so its start and its end show.
fun middleEllipsized(text: String, max: Int = 48): String {
    if (text.length <= max) return text
    val keep = max - 1
    val head = (keep + 1) / 2
    val tail = keep - head
    return text.take(head) + "…" + text.takeLast(tail)
}

// A link as a popup shows it: where it goes, without the scheme, then its
// middle left out and its telling end kept, the first part after #:
// "music.example.com/family/join…#invite=tok_sam". The whole link is in the
// tooltip and what is copied.
fun shownLink(url: String, max: Int = 52): String {
    val bare = url.substringAfter("://")
    val base = bare.substringBefore('#').removeSuffix("/")
    val first = bare.substringAfter('#', "").substringBefore('&').take(20)
    if (first.isEmpty()) return middleEllipsized(base, max)
    val tail = "…#$first"
    val room = (max - tail.length).coerceAtLeast(10)
    if (base.length <= room) return base + tail
    // Cut at a slash, so the address keeps whole parts: "music.example.com…#t=x".
    val cut = base.take(room)
    val slash = cut.lastIndexOf('/')
    return (if (slash > 0) cut.take(slash) else cut) + tail
}

// A server address as people read it: no scheme, no slash at the end.
fun shownServer(server: String): String = server.substringAfter("://").removeSuffix("/")

// The server a family link goes to, path and all: everything before
// /family/. A bare address is its own server.
fun linkServer(url: String): String? =
    url.substringBefore('#').substringBefore("/family/").removeSuffix("/").takeIf { it.contains("://") }

const val INVITE_SUBTITLE = "The link works once and expires in 7 days"

fun inviteCaption(name: String): String = "$name scans this with their phone"

fun inviteNext(name: String): String = "$name picks a password, then signs in on any app."

const val SEND_NEW_LINK = "Send a new link"
const val COPIED = "Copied"
const val LINK_FAILED = "Couldn't make a new link. Try again."
const val TRY_AGAIN = "Try again"
const val MORE_BELOW = "More below"
const val MAKE_NEW_CODE = "Make a new code"
const val STILL_THERE = "Still there?"
const val NEW_CODE = "New code"
const val RENEW_FAILED = "Couldn't get a new code. Trying again."

// Which of a link's two forms shows: through the server's outside address,
// which works anywhere, or through its home address.
enum class LinkReach(val label: String) {
    Anywhere("Anywhere"),
    Home("At home"),
}

// The question over the two choices: the new device is one's own, or
// someone else's.
fun reachQuestion(own: Boolean): String = if (own) "Where will you use it?" else "Where will they use it?"

// The line under the choices: which address the link goes through.
fun reachLine(reach: LinkReach, options: LinkOptions): String? {
    val link = options.linkFor(reach) ?: return null
    val server = linkServer(link) ?: return null
    return if (reach == LinkReach.Home && options.home != null) "Uses your home network (${shownServer(server)})" else "Uses ${originOf(server)}"
}

// An address's scheme and host, without a path: "https://music.example.com".
private fun originOf(server: String): String {
    val scheme = server.substringBefore("://", "https")
    return "$scheme://" + server.substringAfter("://").substringBefore('/')
}

// A link's two forms, as the server answered them, and whether the person
// it is for may listen away from home at all.
data class LinkOptions(
    val anywhere: String?,
    val home: String?,
    val anywhereAvailable: Boolean = true,
    val awayAllowed: Boolean = true,
) {
    // The choice shows when there are two ways to choose between.
    val choosable: Boolean get() = awayAllowed && home != null

    // Anywhere when it can be had, else home.
    val default: LinkReach
        get() = if (home == null || (awayAllowed && anywhereAvailable && anywhere != null)) LinkReach.Anywhere else LinkReach.Home

    // The link for a choice; null for Anywhere before the server has an
    // outside address.
    fun linkFor(reach: LinkReach): String? = when {
        home == null -> anywhere
        reach == LinkReach.Home || !awayAllowed -> home
        anywhereAvailable -> anywhere
        else -> null
    }

    override fun toString() = "LinkOptions(anywhereAvailable=$anywhereAvailable, awayAllowed=$awayAllowed)"
}

// An invite's links: the server's two when it gives them, else the one
// link, which is home only when the server says so.
fun inviteLinkOptions(url: String?, choices: FamilyLinkChoices?, anywhereAvailable: Boolean, homeOnly: Boolean, awayAllowed: Boolean = true): LinkOptions {
    if (choices != null) return LinkOptions(choices.anywhere, choices.home, anywhereAvailable && choices.anywhere != null && !homeOnly, awayAllowed)
    return if (homeOnly || !anywhereAvailable) LinkOptions(null, url, false, awayAllowed) else LinkOptions(url, null, true, awayAllowed)
}

// In place of a QR code when the outside address is missing.
const val SET_OUTSIDE_FIRST = "Set your outside address first"
const val ASK_OWNER_OUTSIDE = "Ask your family owner to set an outside address"
const val OPEN_STATUS = "Open the Status page"

// The dashboard's Status page, where the owner sets the outside address.
fun dashboardStatusPage(server: String): String = "${server.trim().removeSuffix("/")}/admin/#status"

const val ALLOW_AWAY = "Allow listening away from home"
const val ALLOW_AWAY_LINE = "Off keeps them to your home network; their links work only there."

// How long "Copied" shows after a copy, and "New code" after a renewal.
const val COPIED_MS = 2_000L
const val NEW_CODE_MS = 3_000L

// How to sign in from an app, by app: a few numbered steps each. Octo
// comes first; every other Subsonic app takes the same login.
data class AppSteps(val app: String, val steps: List<String>)

fun loginSteps(server: String, username: String): List<AppSteps> = listOf(
    AppSteps(
        "Octo",
        listOf(
            "Open Octo and choose Sign in.",
            "Type $server as the server and $username as the username.",
            "Type your password and sign in.",
        ),
    ),
    AppSteps(
        "Symfonium",
        listOf(
            "Open Settings, then Media providers, then Add media provider, and pick Subsonic.",
            "Type $server as the server address and $username as the username.",
            "Type your password, then tap Test and Save.",
        ),
    ),
    AppSteps(
        "Feishin",
        listOf(
            "Choose Add server and pick Subsonic (or Navidrome).",
            "Type $server as the URL and $username as the username.",
            "Type your password and save.",
        ),
    ),
    AppSteps(
        "Amperfy",
        listOf(
            "On the login screen, type $server as the server URL.",
            "Type $username as the username and your password, then log in.",
        ),
    ),
    AppSteps(
        "Substreamer",
        listOf(
            "Choose Add server, then Subsonic.",
            "Type $server, $username and your password, then connect.",
        ),
    ),
    AppSteps(
        "Tempo",
        listOf(
            "On the login screen, type $server as the server and $username as the username.",
            "Type your password and log in.",
        ),
    ),
)
