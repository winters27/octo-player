package app.winters.octo.ui.library.health

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.data.canRunAll
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.LOOK_UP_TAGS
import app.winters.octo.health.copyName
import app.winters.octo.health.countText
import app.winters.octo.health.duplicateFix
import app.winters.octo.health.fixAllLabel
import app.winters.octo.health.fixMeaning
import app.winters.octo.health.origin
import app.winters.octo.health.tagName
import app.winters.octo.health.title
import app.winters.octo.health.words
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.rememberLast
import app.winters.octo.ui.common.rememberOpenedBeside

// The pop-up over a Library health page that shows a fix before it runs,
// how far it has got, and how it went, with its Undo.

private val SheetWidth = 328.dp

// How long a preview list gets before the rest is only counted.
private const val PREVIEW_LINES = 100

@Composable
fun HealthSheetHost(vm: HealthViewModel) {
    val open = vm.sheet != null
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = vm::close,
        backdrop = LocalHaze.current,
        title = "Library health",
        heightShare = 0.8f,
    ) {
        // Kept while the card fades, after it was closed.
        val sheet = rememberLast(vm.sheet) ?: return@GlassPopup
        when (sheet) {
            is HealthSheet.Duplicate -> DuplicateSheet(sheet, vm)
            is HealthSheet.FixAll -> FixAllSheet(sheet, vm)
            is HealthSheet.Join -> SheetPage(
                title = sheet.title,
                buttons = { SheetButtons("Join", onConfirm = { vm.run(sheet.steps) }, onCancel = vm::close) },
            ) { SheetWords(sheet.words) }
            is HealthSheet.Upgrade -> SheetPage(
                title = HealthCheck.NoLength.title(),
                buttons = { SheetButtons(HealthCheck.NoLength.fixAllLabel(sheet.asks.size), onConfirm = { vm.upgrade(sheet.asks) }, onCancel = vm::close) },
            ) {
                SheetWords(HealthCheck.NoLength.fixMeaning())
                PreviewLines(sheet.asks.map { it.title to null })
            }
            HealthSheet.LookUp -> LookUpSheet(vm)
            HealthSheet.Running -> RunningSheet(vm)
            is HealthSheet.Done -> {
                val actions by vm.actions.collectAsStateWithLifecycle()
                val undo = sheet.outcome.undo
                SheetPage(
                    title = "Done",
                    buttons = {
                        if (actions.canRunAll(undo)) {
                            SheetButtons("Undo", onConfirm = { vm.run(undo, undo = true, on = sheet.server) }, onCancel = vm::close, cancel = "Close")
                        } else {
                            AccentButton("Close", onClick = vm::close)
                        }
                    },
                ) { SheetWords(sheet.outcome.summary(), live = true) }
            }
        }
    }
}

// One set of copies: which is kept and why, which go to the server's
// trash, the kept copy's blank tags filled from the others (each can be
// left out), and, where the copies disagree, which value to keep.
@Composable
private fun DuplicateSheet(sheet: HealthSheet.Duplicate, vm: HealthViewModel) {
    val offers by vm.offers.collectAsStateWithLifecycle()
    val fix = remember(sheet) { duplicateFix(sheet.group, ServerCopyHealth, sheet.keep ?: sheet.group.best) }
    // Tags left out, and the copy each differing tag is taken from.
    val skipped = remember(sheet) { mutableStateMapOf<String, Boolean>() }
    val choices = remember(sheet) { mutableStateMapOf<String, String>() }
    val fills = fix.fills.filter { skipped[it.tag] != true }
    val changes = fills + fix.picked(choices)
    SheetPage(
        title = sheet.title,
        buttons = { SheetButtons("Fix", onConfirm = { vm.run(fix.serverSteps(changes, offers.edit)) }, onCancel = vm::close) },
    ) {
        SheetHeading("Keeps")
        SheetLine(copyLine(fix.keep), fix.why)
        SheetHeading("Moves to the server's trash")
        fix.remove.forEach { copy ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SheetLine(copyLine(copy), null) }
                RowButton("Keep", "Keep this copy instead") { vm.open(sheet.copy(keep = copy)) }
            }
        }
        if (offers.edit && fix.fills.isNotEmpty()) {
            SheetHeading("Fills in")
            fix.fills.forEach { change ->
                CheckRow(change.words(), checked = skipped[change.tag] != true) { skipped[change.tag] = !it }
            }
        }
        if (offers.edit && fix.differs.isNotEmpty()) {
            SheetHeading("Where the copies disagree")
            fix.differs.forEach { choice ->
                SheetWords(tagName(choice.tag), muted = true)
                val keptFrom = choices[choice.tag] ?: fix.keep.nativeId
                // Each value once, from the first copy that has it.
                choice.values.entries.distinctBy { it.value }.forEach { (copyId, value) ->
                    val picked = choice.values[keptFrom] == value
                    PickRow(value, picked) { choices[choice.tag] = copyId }
                }
            }
        }
        fix.note?.let { SheetWords(it, muted = true) }
    }
}

// A copy by its name and sound, and its album.
private fun copyLine(copy: SourceTrackEntity): String {
    val album = copy.album.trim()
    return if (album.isEmpty()) copyName(copy, ServerCopyHealth) else "${copyName(copy, ServerCopyHealth)}, on $album"
}

// Every finding's fix, one line each, and the one button that does them all.
@Composable
private fun FixAllSheet(sheet: HealthSheet.FixAll, vm: HealthViewModel) {
    val preview = sheet.preview
    SheetPage(
        title = sheet.check.title(),
        buttons = { SheetButtons(sheet.check.fixAllLabel(preview.count), onConfirm = { vm.run(preview.steps) }, onCancel = vm::close) },
    ) {
        SheetWords(sheet.check.fixMeaning())
        PreviewLines(preview.lines)
    }
}

@Composable
private fun PreviewLines(lines: List<Pair<String, String?>>) {
    lines.take(PREVIEW_LINES).forEach { (title, detail) -> SheetLine(title, detail) }
    if (lines.size > PREVIEW_LINES) SheetWords("And ${countText(lines.size - PREVIEW_LINES, "more", "more")}.", muted = true)
}

// Songs being looked up, then everything found, each change ticked or not
// to begin with as the shared rules say, and one button that writes the
// ticked ones.
@Composable
private fun LookUpSheet(vm: HealthViewModel) {
    val state = vm.lookups
    // Changes ticked off, by song and tag.
    val skipped = remember(state.round) { mutableStateMapOf<String, Boolean>() }
    fun key(serverId: String, tag: String) = "$serverId/$tag"
    val picked = state.songs.associate { song ->
        song to song.changes.filter { (change, on) -> skipped[key(song.serverId, change.tag)]?.not() ?: on }.map { it.first }
    }
    val count = picked.values.sumOf { it.size }
    val title = if (state.total == 1) LOOK_UP_TAGS else "Looking up ${countText(state.total, "song", "songs")}"
    SheetPage(
        title = title,
        buttons = {
            when {
                !state.finished -> GlazeButton("Stop", onClick = vm::stopLookUp)
                count == 0 -> AccentButton("Close", onClick = vm::close)
                else -> SheetButtons(
                    if (count == 1) "Write 1 change" else "Write $count changes",
                    onConfirm = { vm.run(picked.mapNotNull { (song, changes) -> retagStep(song.serverId, song.track.title, changes) }) },
                    onCancel = vm::close,
                )
            }
        },
    ) {
        if (!state.finished) {
            SheetWords("Looked up ${state.songs.size} of ${state.total}", muted = true, live = true)
            ProgressLine(state.songs.size, state.total)
        } else if (state.songs.none { it.changes.isNotEmpty() }) {
            SheetWords("Nothing to change: the tags found say what the files say already, or nothing was found.", muted = true)
        }
        state.songs.forEach { song ->
            if (state.total > 1) SheetHeading(song.track.title)
            val lookup = song.lookup
            when {
                song.problem != null -> SheetWords(song.problem, muted = true)
                lookup == null || !lookup.found -> SheetWords(lookup?.detail?.takeIf(String::isNotBlank) ?: "Nothing was found for this song.", muted = true)
                song.changes.isEmpty() -> SheetWords("${lookup.origin()} The file says the same already.", muted = true)
                else -> {
                    SheetWords(lookup.origin(), muted = true)
                    song.changes.forEach { (change, first) ->
                        val k = key(song.serverId, change.tag)
                        CheckRow(change.words(), checked = skipped[k]?.not() ?: first) { skipped[k] = !it }
                    }
                }
            }
        }
    }
}

// Steps on their way: how many are done, with a way to stop between two.
@Composable
private fun RunningSheet(vm: HealthViewModel) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val run = progress
    SheetPage(title = "Changing the files", buttons = { GlazeButton("Stop", onClick = vm::stop) }) {
        if (run == null) {
            SheetWords("Starting", muted = true)
        } else {
            SheetWords("${run.done} of ${run.total} done", muted = true, live = true)
            ProgressLine(run.done, run.total)
        }
        SheetWords("Closing this lets it carry on. It says how it went when it is done.", muted = true)
    }
}

// A sheet: its heading, then what it shows, which scrolls, then its
// buttons, which stay in view.
@Composable
private fun SheetPage(title: String, buttons: @Composable () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(SheetWidth).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Text(title, style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 4.dp), content = body)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
    }
}

@Composable
private fun SheetButtons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, cancel: String = "Cancel") {
    AccentButton(confirm, onClick = onConfirm)
    GlazeButton(cancel, onClick = onCancel)
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = OctoType.label, color = OctoColors.TextPrimary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).semantics { heading() })
}

@Composable
private fun SheetWords(text: String, muted: Boolean = false, live: Boolean = false) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = if (muted) OctoColors.TextMuted else OctoColors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    )
}

// One thing a fix does, and a line under it.
@Composable
private fun SheetLine(title: String, detail: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
        detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
    }
}

private val MarkShape = RoundedCornerShape(5.dp)

// A change that can be left out: a tick in a box when it is in.
@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(checked, MarkShape)
        Text(text, style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}

// One of a few values, the one kept marked.
@Composable
private fun PickRow(text: String, picked: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(picked, CircleShape)
        Text(text, style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Mark(on: Boolean, shape: Shape) {
    Box(
        Modifier
            .size(20.dp)
            .clip(shape)
            .then(if (on) Modifier.background(OctoColors.Accent) else Modifier.border(1.5.dp, OctoColors.TextMuted, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.Background, modifier = Modifier.size(14.dp))
    }
}

// How far along, as a thin bar.
@Composable
private fun ProgressLine(done: Int, total: Int) {
    val share = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(OctoColors.TextPrimary.copy(alpha = 0.1f)),
    ) {
        Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(OctoColors.Accent))
    }
}
