package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyLinkChoices
import java.time.Instant

// The words and rules of the Add a device and invite popups, the same on
// the phone and the desktop.

// What the Add a device popup shows: the pair code with its QR code, or
// the app password for another app.
enum class DeviceSheetView { Code, OtherApps }

// The Add a device popup's state: for whom, what the server answered, which
// view shows, and how asking for it goes. Each popup opened has its own
// `id`, so an answer for one already closed is dropped.
data class DeviceSheet(
    val id: Int = 0,
    // The member's name when a manager adds a device for someone else.
    val forName: String? = null,
    val forUsername: String? = null,
    // The name typed for the new device, if any.
    val deviceName: String = "",
    // Whether whoever it is for may listen away from home; when not, only
    // the home link is offered.
    val awayAllowed: Boolean = true,
    val code: FamilyDeviceAdded? = null,
    val password: FamilyDeviceAdded? = null,
    val view: DeviceSheetView = DeviceSheetView.Code,
    val loading: Boolean = false,
    val error: String? = null,
    // When it opened, by the model's clock: renewing stops an hour later.
    val openedAt: Long = 0,
    // Renewing the code failed; it is tried again at `retryAt`.
    val refreshFailed: Boolean = false,
    val retryAt: Long = 0,
    // Open an hour: renewing stopped, and the popup asks.
    val stale: Boolean = false,
    // How many times the code was renewed, for the "New code" notice.
    val renewed: Int = 0,
) {
    val title: String
        get() = when (view) {
            DeviceSheetView.Code -> if (forName != null) "Add a device for $forName" else "Add a device"
            DeviceSheetView.OtherApps -> if (forName != null) "Add Symfonium or another app for $forName" else "Add Symfonium or another app"
        }

    // What the current view shows: the pair code or the app password.
    val shown: FamilyDeviceAdded? get() = if (view == DeviceSheetView.Code) code else password

    // Nothing to show yet: the skeleton stands in.
    val waiting: Boolean get() = shown == null && error == null

    // Never the code or password, in a log or anywhere else.
    override fun toString() = "DeviceSheet(id=$id, view=$view, loading=$loading)"
}

// The server address and username a device signs in with: the server's own
// answer first, then this app's.
fun DeviceSheet.server(fallback: String): String = shown?.server?.takeIf(String::isNotBlank) ?: fallback

fun DeviceSheet.username(fallback: String): String = shown?.username?.takeIf(String::isNotBlank) ?: forUsername ?: fallback

// The invite popup's state: who it is for, their link once made, and how
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
) {
    val title: String get() = "Invite $name"

    val options: LinkOptions get() = inviteLinkOptions(url, links, anywhereAvailable, homeOnly, awayAllowed)

    override fun toString() = "InviteSheet(name=$name, loading=$loading)"
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

// The header's line under the title: "Works once · expires in 9:42", or
// without an expiry "Works once". At the end a new code is on its way.
fun countdownLine(seconds: Long?): String = when {
    seconds == null -> "Works once"
    seconds <= 0 -> "Works once · getting a new code"
    else -> "Works once · expires in ${minutesSeconds(seconds)}"
}

// Under a minute the countdown shows in the warning color.
fun countdownWarns(seconds: Long?): Boolean = seconds != null && seconds < 60

// What a screen reader is told about the countdown: only at one minute
// left and on expiry, so it is not read every second.
fun countdownAnnouncement(seconds: Long?): String? = when {
    seconds == null -> null
    seconds <= 0 -> "This code expired. Getting a new one."
    seconds <= 60 -> "One minute left"
    else -> null
}

// A pair code as people read it: "482 913".
fun groupedCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

// An app password in groups of four, "ABCD-EFGH-JKMN-PQRS", whether or not
// the server grouped it.
fun groupedPassword(password: String): String =
    if (password.contains('-')) password else password.chunked(4).joinToString("-")

// A long link with its middle left out, so its start and its end show:
// "https://music.example.com/fam…#u=alex&c=482913". The full link stays in
// the tooltip and in what is copied.
fun middleEllipsized(text: String, max: Int = 48): String {
    if (text.length <= max) return text
    val keep = max - 1
    val head = (keep + 1) / 2
    val tail = keep - head
    return text.take(head) + "…" + text.takeLast(tail)
}

// A link as the popup shows it: where it goes, without the scheme or the
// part after #, which only carries the code. The whole link is in the
// tooltip and what is copied.
fun shownLink(url: String, max: Int = 40): String {
    val bare = url.substringBefore('#').substringAfter("://").removeSuffix("/")
    return middleEllipsized(bare, max)
}

// The server address a join link goes to, path and all: what to type in
// Octo for that link's network.
fun linkServer(url: String): String? = url.substringBefore('#').substringBefore("/family/join").removeSuffix("/").takeIf { it.contains("://") }

// The invite's header line.
const val INVITE_SUBTITLE = "The link works once and expires in 7 days"

fun inviteCaption(name: String): String = "$name scans this with their phone"

fun inviteNext(name: String): String = "$name picks a password, then adds their devices."

const val CODE_QR_CAPTION = "Scan with the new phone's camera"
const val CODE_QR_LABEL = "QR code for the join link"
const val OPEN_LINK_CAPTION = "Or open this link on that device"
const val TYPE_IT_CAPTION = "Or type it in Octo: Join with a family code"
const val OTHER_APPS_LINK = "Using Symfonium or another app instead?"
const val OTHER_APPS_SUBTITLE = "Shown once. Copy it now."
const val BACK_TO_QR = "Back to the QR code"
const val SHOWN_ONCE = "Shown once"
const val MAKE_NEW_CODE = "Make a new code"
const val STILL_THERE = "Still there?"
const val NEW_CODE = "New code"
const val RENEW_FAILED = "Couldn't get a new code. Trying again."
const val SEND_NEW_LINK = "Send a new link"
const val COPIED = "Copied"
const val CODE_FAILED = "Couldn't make a code. Try again."
const val PASSWORD_FAILED = "Couldn't make an app password. Try again."
const val LINK_FAILED = "Couldn't make a new link. Try again."
const val TRY_AGAIN = "Try again"

// Which of a link's two forms shows: through the server's outside address,
// which works anywhere, or through its home address.
enum class LinkReach(val label: String) {
    Anywhere("Works anywhere"),
    Home("At home only"),
}

// A link's two forms, as the server answered them, and whether the person
// it is for may listen away from home at all.
data class LinkOptions(
    val anywhere: String?,
    val home: String?,
    val anywhereAvailable: Boolean = true,
    val awayAllowed: Boolean = true,
) {
    // The switch shows when there are two ways to choose between.
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

// A device's code as links: the server's two when it gives them, else the
// one link (its own, or one made from its address and code), which is home
// only when the server says so.
fun deviceLinkOptions(added: FamilyDeviceAdded, server: String, awayAllowed: Boolean = true): LinkOptions {
    val choices = added.links
    if (choices != null) return LinkOptions(choices.anywhere, choices.home, added.anywhereAvailable && choices.anywhere != null && !added.homeOnly, awayAllowed)
    val one = addedDeviceLink(added, server)
    return if (added.homeOnly || !added.anywhereAvailable) LinkOptions(null, one, false, awayAllowed) else LinkOptions(one, null, true, awayAllowed)
}

// An invite's links, the same way.
fun inviteLinkOptions(url: String?, choices: FamilyLinkChoices?, anywhereAvailable: Boolean, homeOnly: Boolean, awayAllowed: Boolean = true): LinkOptions {
    if (choices != null) return LinkOptions(choices.anywhere, choices.home, anywhereAvailable && choices.anywhere != null && !homeOnly, awayAllowed)
    return if (homeOnly || !anywhereAvailable) LinkOptions(null, url, false, awayAllowed) else LinkOptions(url, null, true, awayAllowed)
}

// Under a home link's QR code, and in place of one when the outside address
// is missing.
const val HOME_ONLY = "This link only works on your home network"
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

// A code is renewed this long before it expires, a failed renewal is tried
// again this often, and an open popup renews for this long at most.
const val RENEW_LEAD_MS = 5_000L
const val RENEW_RETRY_MS = 10_000L
const val RENEW_FOR_MS = 60 * 60 * 1000L

// How to sign in from another app, by app: a few numbered steps each.
data class AppSteps(val app: String, val steps: List<String>)

fun otherAppSteps(server: String, username: String): List<AppSteps> = listOf(
    AppSteps(
        "Symfonium",
        listOf(
            "Open Settings, then Media providers, then Add media provider, and pick Subsonic.",
            "Type $server as the server address and $username as the username.",
            "Paste the app password as the password, then tap Test and Save.",
        ),
    ),
    AppSteps(
        "Feishin",
        listOf(
            "Choose Add server and pick Subsonic (or Navidrome).",
            "Type $server as the URL and $username as the username.",
            "Paste the app password as the password and save.",
        ),
    ),
    AppSteps(
        "Amperfy",
        listOf(
            "On the login screen, type $server as the server URL.",
            "Type $username as the username and paste the app password, then log in.",
        ),
    ),
    AppSteps(
        "Substreamer",
        listOf(
            "Choose Add server, then Subsonic.",
            "Type $server, $username and paste the app password, then connect.",
        ),
    ),
    AppSteps(
        "Tempo",
        listOf(
            "On the login screen, type $server as the server and $username as the username.",
            "Paste the app password as the password and log in.",
        ),
    ),
)
