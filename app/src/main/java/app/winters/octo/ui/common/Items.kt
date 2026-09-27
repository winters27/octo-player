package app.winters.octo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.shownLengthMs
import app.winters.octo.ui.menu.CollectionTarget
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.menu.SongMenuContext
import kotlinx.coroutines.launch

// What TalkBack says a long press does on any card or row with a menu.
private const val MoreOptions = "More options"

private val RowShape = RoundedCornerShape(12.dp)

// Opens the menu on a long press, with a little buzz.
private fun Modifier.pressOrHold(haptics: HapticFeedback, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    if (onLongClick == null) return clickable(onClick = onClick)
    return combinedClickable(
        onClick = onClick,
        onLongClickLabel = MoreOptions,
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        },
    )
}

// An album as a picture with its name and artist under it. No card
// outline: the picture carries the edge. A long press opens its menu.
@Composable
fun AlbumCard(album: AlbumEntity, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp) {
    val menus = LocalSongMenu.current.collections
    AlbumCard(album.artwork, album.title, album.artist, onClick, { menus.open(CollectionTarget.Album(album.id)) }, modifier, width, outside = false)
}

// An album on the server, not in the library, drawn the same way with the
// not-in-library mark on its cover.
@Composable
fun AlbumCard(album: OnlineAlbum, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 150.dp) =
    AlbumCard(album.artwork, album.title, album.artist, onClick, null, modifier, width, outside = true)

@Composable
private fun AlbumCard(
    artwork: String?,
    title: String,
    artist: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    width: Dp?,
    outside: Boolean,
) {
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .pressOrHold(LocalHapticFeedback.current, onClick, onLongClick),
    ) {
        ArtworkFill(artwork, outside = outside)
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artist,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// A song as a picture with its title and artist under it, for rows of
// songs. A tap plays; a long press opens the song's menu. A song found
// online carries the not-in-library mark on its picture, and nothing more
// by its artist.
@Composable
fun SongCard(track: TrackEntity, onClick: () -> Unit) {
    val menu = LocalSongMenu.current
    Column(
        Modifier
            .width(150.dp)
            .alpha(if (LocalOfflineMarks.current.isOutOfReach(track)) OutOfReachAlpha else 1f)
            .pressOrHold(LocalHapticFeedback.current, onClick) { menu.open(track.id) },
    ) {
        ArtworkFill(track.artwork, outside = isOutsideLibrary(track.id))
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                track.artist,
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            SourceMark(track)
        }
    }
}

// Marks a song that is not on the phone, so it streams from the server,
// or anything else that lives on the server.
@Composable
internal fun CloudMark(description: String = "Streams from your server") {
    Icon(
        painterResource(OctoIcons.Cloud),
        contentDescription = description,
        tint = OctoColors.TextMuted,
        modifier = Modifier.size(14.dp),
    )
}

// What sits at the start of a song row.
sealed interface SongLead {
    data object Artwork : SongLead
    data class Number(val track: Int?) : SongLead
}

// The album a row names. A song found online often has its own title for
// an album, which says nothing new, so that is left off.
fun rowAlbum(track: TrackEntity): String =
    track.album.takeUnless { isFind(track.id) && it.trim().equals(track.title.trim(), ignoreCase = true) }.orEmpty()

// "Artist • Album" under a song's title.
fun songSubtitle(track: TrackEntity): String =
    listOf(track.artist, rowAlbum(track)).filter { it.isNotEmpty() }.joinToString(" • ")

// How wide the add button is in a row, and its plus.
private val AddButtonSize = 40.dp
private val AddIconSize = 22.dp

// A song line; tapping it plays it. `trailing` goes after the length, such
// as a drag handle. `menuContext` says what page the row is on, for its
// menu. In a list that picks songs, `selectKey` is the row's key; a tap
// then picks it instead. A swipe right puts it next in the queue, on lists
// where a swipe does nothing else (`swipeToPlayNext`).
// A song found online that is not in the library carries a small plus on
// its artwork. In a list that offers adding (`offerAdd`), it gets the add
// button at the end instead, and the row says it once. Every other row in
// such a list keeps that space empty, so the lengths line up.
@Composable
fun SongRow(
    track: TrackEntity,
    lead: SongLead = SongLead.Artwork,
    subtitle: String? = songSubtitle(track),
    trailing: (@Composable () -> Unit)? = null,
    menuContext: SongMenuContext = SongMenuContext(),
    selectKey: String = track.id,
    swipeToPlayNext: Boolean = true,
    offerAdd: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val sign = rowAddSign(track.id, LocalAdoptedFinds.current, offerAdd)
    val end: (@Composable () -> Unit)? = when {
        sign == AddSign.Button -> ({ AddToLibraryButton(track, size = AddButtonSize, iconSize = AddIconSize) })
        trailing == null && rowKeepsAddSpace(sign, offerAdd) -> ({ Spacer(Modifier.width(AddButtonSize)) })
        else -> trailing
    }
    val mark = sign == AddSign.Mark
    if (swipeToPlayNext) {
        val menu = LocalSongMenu.current
        // No swiping while the list is picking songs.
        val selecting = LocalSongSelection.current?.active == true
        PlayNextSwipe(enabled = !selecting, onSwiped = { menu.quick?.playNext(track.id) }) {
            SongLine(track, lead, subtitle, end, mark, menuContext, selectKey, onClick)
        }
    } else {
        SongLine(track, lead, subtitle, end, mark, menuContext, selectKey, onClick)
    }
}

@Composable
private fun SongLine(
    track: TrackEntity,
    lead: SongLead,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    outside: Boolean,
    menuContext: SongMenuContext,
    selectKey: String,
    onClick: (() -> Unit)?,
) {
    val menu = LocalSongMenu.current
    val haptics = LocalHapticFeedback.current
    val selection = LocalSongSelection.current
    val selecting = selection?.active == true
    val picked = selection?.isPicked(selectKey) == true
    val now = LocalNowPlayingId.current
    val isNow = now.trackId == track.id
    Box {
        // A picked song sits in a darker pill.
        if (picked) GlazeSelected(Modifier.matchParentSize().padding(horizontal = 8.dp, vertical = 2.dp), RowShape)
        Row(
            Modifier
                .fillMaxWidth()
                // A tap plays, or picks while the list is picking; a long
                // press opens the song's menu.
                .combinedClickable(
                    onClick = { if (selecting) selection.toggle(selectKey) else onClick?.invoke() },
                    onLongClickLabel = MoreOptions,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu.open(track.id, menuContext.copy(selection = selection, selectKey = selectKey))
                    },
                )
                .semantics {
                    if (selecting) selected = picked
                    customActions = listOf(
                        // Like the swipe it stands in for, it says so, with an Undo.
                        CustomAccessibilityAction("Play next") {
                            menu.quick?.playNext(track.id)
                            true
                        },
                        CustomAccessibilityAction("Add to queue") {
                            menu.quick?.addToQueue(track.id)
                            true
                        },
                    )
                }
                // At least a row tall; taller when large text needs it.
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 4.dp)
                // With no connection, a song that cannot play is drawn faint.
                .alpha(if (LocalOfflineMarks.current.isOutOfReach(track)) OutOfReachAlpha else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (lead) {
                SongLead.Artwork -> Box(contentAlignment = Alignment.Center) {
                    Artwork(track.artwork, 44.dp, shape = RoundedCornerShape(6.dp), outside = outside)
                    when {
                        picked -> LeadMark { PickedMark() }
                        isNow -> LeadMark { NowPlayingBars(now.playing, Modifier.size(16.dp)) }
                    }
                }
                is SongLead.Number -> Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        picked -> PickedMark()
                        isNow -> NowPlayingBars(now.playing, Modifier.size(14.dp))
                        else -> Text(
                            lead.track?.toString() ?: "",
                            style = OctoType.caption,
                            color = OctoColors.TextMuted,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A song found online has no mark here: its artwork or add
                // button says it. Its length may only be learned by playing it.
                SourceMark(track)
                LengthSlot(shownLengthMs(track.durationMs, LocalLearnedLengths.current[track.id]))
            }
            trailing?.invoke()
        }
    }
}

// Shaded over a row's artwork, so a mark on it reads.
private val LeadShade = Color.Black.copy(alpha = 0.5f)

@Composable
private fun LeadMark(mark: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).background(LeadShade, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { mark() }
}

@Composable
private fun PickedMark() {
    Icon(painterResource(OctoIcons.Select), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(22.dp))
}

// A swipe right puts the song next: the row springs back once it has, with
// "Play next" showing behind it on the way.
@Composable
private fun PlayNextSwipe(enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromEndToStart = false,
        gesturesEnabled = enabled,
        onDismiss = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onSwiped()
            scope.launch { swipe.reset() }
        },
        backgroundContent = { PlayNextBackground(swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd, RowShape) },
    ) {
        // Solid under the row while it moves, so it hides what it passes
        // over; clear at rest, so the page's glow shows.
        val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Box(Modifier.background(if (swiping) OctoColors.Background else Color.Transparent, RowShape)) { content() }
    }
}

// An artist line: round picture, name, album count. A long press opens the
// artist's menu.
@Composable
fun ArtistRow(artist: ArtistEntity, onClick: () -> Unit) {
    val menus = LocalSongMenu.current.collections
    Row(
        Modifier
            .fillMaxWidth()
            .pressOrHold(LocalHapticFeedback.current, onClick) { menus.open(CollectionTarget.Artist(artist.id)) }
            // At least a row tall; taller when large text needs it.
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artist.artwork, 48.dp, shape = CircleShape)
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(albums(artist.albumCount), style = OctoType.caption, color = OctoColors.TextMuted)
        }
    }
}

// An artist as a round picture with the name under it, for rows. A long
// press opens the artist's menu.
@Composable
fun ArtistCircle(artist: ArtistEntity, onClick: () -> Unit) {
    val menus = LocalSongMenu.current.collections
    ArtistCircle(artist.artwork, artist.name, onClick, { menus.open(CollectionTarget.Artist(artist.id)) }, outside = false)
}

// An artist on the server, drawn the same way. Not in the library unless
// said otherwise (`outside`), so with the not-in-library mark.
@Composable
fun ArtistCircle(artist: OnlineArtist, outside: Boolean = true, onClick: () -> Unit) =
    ArtistCircle(artist.artwork, artist.name, onClick, null, outside = outside)

@Composable
private fun ArtistCircle(artwork: String?, name: String, onClick: () -> Unit, onLongClick: (() -> Unit)?, outside: Boolean) {
    Column(
        Modifier.width(96.dp).pressOrHold(LocalHapticFeedback.current, onClick, onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, 96.dp, shape = CircleShape, outside = outside)
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = OctoType.caption,
            color = OctoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// The first letter a list is grouped under; anything else goes under "#".
fun indexLetter(sortKey: String): Char =
    sortKey.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
