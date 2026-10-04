package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.onServer
import app.winters.octo.health.AlbumJoin
import app.winters.octo.health.DuplicateFix
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthFields
import app.winters.octo.health.HealthTag
import app.winters.octo.health.TagChange
import app.winters.octo.health.albumJoin
import app.winters.octo.health.copyName
import app.winters.octo.health.heading
import app.winters.octo.health.words
import app.winters.octo.health.duplicateFix
import app.winters.octo.health.fillsFromAlbum
import app.winters.octo.health.steps
import app.winters.octo.subsonic.LibraryActions

// Library health's fixes on the phone, planned from what the checks found.
// The checks run on the library as the phone shows it, which joins copies
// from several places; every step carries the id of the song's copy on
// the signed-in Octo server, and a song with no copy there gets none.

// How many songs one bulk look-up takes at most, so it stays short.
const val LOOKUP_BATCH = 25

// A source's copy as its server knows it: by its id there. The copies of
// one set are all in one place, so that id tells them apart.
object ServerCopyHealth : HealthFields<SourceTrackEntity> by SourceCopyHealth {
    override fun id(song: SourceTrackEntity) = song.nativeId
}

// What Library health offers to do, from what the server lets this user
// do. A server with no library actions, or a user who is not an admin,
// gets none, and the pages stay as they were: something to read.
data class HealthOffers(
    // Keep the best copy of a song and send the others to the trash.
    val fixDuplicates: Boolean = false,
    // Write tags: the kept copy's blanks, a song's album's year, a look-up.
    val edit: Boolean = false,
    val join: Boolean = false,
    val lookUp: Boolean = false,
    val covers: Boolean = false,
    val upgrade: Boolean = false,
    val restore: Boolean = false,
    val remove: Boolean = false,
) {
    // Whether a check's page has its button for every finding.
    fun fixesAll(check: HealthCheck): Boolean = when (check) {
        HealthCheck.Duplicates -> fixDuplicates
        HealthCheck.SplitAlbums -> join
        HealthCheck.NoCover -> covers
        HealthCheck.NoLength -> upgrade
        HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> edit
        HealthCheck.NoTrackNumber -> lookUp
    }

    // Whether each song of a check can be looked up by itself.
    fun looksUp(check: HealthCheck): Boolean = lookUp && check.tag != null && check != HealthCheck.NoCover
}

fun healthOffers(actions: LibraryActions?): HealthOffers {
    if (actions == null) return HealthOffers()
    return HealthOffers(
        fixDuplicates = actions.canRemove,
        edit = actions.canEdit,
        join = actions.canJoinAlbums,
        // A look-up is only worth showing when what it finds can be written.
        lookUp = actions.canLookUp && actions.canEdit,
        covers = actions.canAddCover,
        upgrade = actions.canUpgrade,
        restore = actions.canRestore,
        remove = actions.canRemove,
    )
}

// The plan for the set of copies at `index`, keeping `keep` (the best copy
// unless a person picked another). Null for a set that is not on the
// signed-in server's disk.
fun PhoneHealth.duplicatePlan(index: Int, source: String?, keep: SourceTrackEntity? = null): DuplicateFix<SourceTrackEntity>? {
    val group = copyGroups.getOrNull(index) ?: return null
    if (source == null || group.best.sourceId != source) return null
    val kept = keep?.let { k -> group.copies.firstOrNull { it.id == k.id } } ?: group.best
    return duplicateFix(group, ServerCopyHealth, kept)
}

// The source's copy that one library song of a set stands for.
fun PhoneHealth.copyIn(index: Int, trackId: String): SourceTrackEntity? =
    copyGroups.getOrNull(index)?.copies?.firstOrNull { it.mergedId == trackId }

// The tags a person picked from the copies' differing values, by tag: the
// copy whose value is kept. One picked from the kept copy changes nothing.
fun DuplicateFix<SourceTrackEntity>.picked(choices: Map<String, String>): List<TagChange> = differs.mapNotNull { choice ->
    val from = choices[choice.tag] ?: return@mapNotNull null
    val now = choice.values[keep.nativeId]
    val value = choice.values[from] ?: return@mapNotNull null
    if (value == now) null else TagChange(choice.tag, now, value, from)
}

// The steps for one set: fill the kept copy's tags when the server can
// write them, then send the others to the trash.
fun DuplicateFix<SourceTrackEntity>.serverSteps(changes: List<TagChange>, edit: Boolean): List<FixStep> =
    steps(ServerCopyHealth, if (edit) changes else emptyList())

// Every set on the signed-in server, each with the steps "Fix all" takes:
// its fills, never a differing value, and the others removed.
fun PhoneHealth.fixAllDuplicates(source: String?, edit: Boolean): List<Pair<DuplicateFix<SourceTrackEntity>, List<FixStep>>> =
    copyGroups.indices.mapNotNull { index ->
        val plan = duplicatePlan(index, source) ?: return@mapNotNull null
        plan to plan.serverSteps(plan.fills, edit)
    }

// The join for the split album at `index`, led by the first song of its
// largest part the server has, with its steps. Null when the server has
// nothing of it to move.
fun PhoneHealth.joinPlan(index: Int, serverIds: Map<String, String>): Pair<AlbumJoin<TrackEntity>, List<FixStep>>? {
    val album = report.splitAlbums.getOrNull(index) ?: return null
    val lead = album.parts[0].songs.firstOrNull { it.id in serverIds } ?: return null
    val join = albumJoin(album).copy(lead = lead)
    val steps = join.steps(fields).onServer(serverIds)
    return if (steps.isEmpty()) null else join to steps
}

// Every split album the server can join, one after another.
fun PhoneHealth.joinAll(serverIds: Map<String, String>): List<FixStep> =
    report.splitAlbums.indices.flatMap { joinPlan(it, serverIds)?.second.orEmpty() }

// A check's missing tag filled where the rest of the album agrees, one
// step per song the server has.
fun PhoneHealth.fillSteps(check: HealthCheck, serverIds: Map<String, String>): List<FixStep> {
    val tag = check.tag ?: return emptyList()
    val missing = report.missing[tag].orEmpty()
    return fillsFromAlbum(missing, tracks, tag, fields)
        .map { (song, change) -> FixStep.Retag(song.id, song.title, mapOf(change.tag to change.value)) }
        .onServer(serverIds)
}

// A cover looked for, for each song without one the server has.
fun PhoneHealth.coverSteps(serverIds: Map<String, String>): List<FixStep> =
    report.missing[HealthTag.Cover].orEmpty()
        .map { FixStep.AddCover(it.id, it.title) }
        .onServer(serverIds)

// The songs one bulk look-up takes: the first the server has, at most a
// batch, each with its id there.
fun lookupBatch(songs: List<TrackEntity>, serverIds: Map<String, String>, limit: Int = LOOKUP_BATCH): List<Pair<TrackEntity, String>> =
    songs.mapNotNull { song -> serverIds[song.id]?.let { song to it } }.distinctBy { it.second }.take(limit)

// The step that writes the changes picked for one song, if any.
fun retagStep(serverId: String, title: String, changes: List<TagChange>): FixStep.Retag? =
    if (changes.isEmpty()) null else FixStep.Retag(serverId, title, changes.associate { it.tag to it.value })

// What a check's button for everything would do, shown before it runs:
// one line per set, album or song, and the steps it takes.
data class FixPreview(val count: Int, val lines: List<Pair<String, String?>>, val steps: List<FixStep>)

// The preview for a check's fix of everything the server can fix. Songs
// with no copy on the server, and anything it cannot do, are left out.
fun PhoneHealth.fixAllPreview(check: HealthCheck, offers: HealthOffers, source: String?, serverIds: Map<String, String>): FixPreview {
    if (!offers.fixesAll(check)) return FixPreview(0, emptyList(), emptyList())
    return when (check) {
        HealthCheck.Duplicates -> {
            val plans = fixAllDuplicates(source, offers.edit)
            val lines = plans.map { (plan, steps) ->
                val fills = steps.filterIsInstance<FixStep.Retag>().flatMap { it.tags.map { (tag, value) -> TagChange(tag, null, value).words() } }
                val removes = "Removes " + plan.remove.joinToString(", ") { copyName(it, ServerCopyHealth) } + "."
                val detail = if (fills.isEmpty()) removes else "$removes Fills in ${fills.joinToString(", ")}."
                "Keeps ${copyName(plan.keep, ServerCopyHealth)}" to detail
            }
            FixPreview(plans.size, lines, plans.flatMap { it.second })
        }
        HealthCheck.SplitAlbums -> {
            val joins = report.splitAlbums.indices.mapNotNull { index -> joinPlan(index, serverIds)?.let { report.splitAlbums[index] to it } }
            FixPreview(joins.size, joins.map { (album, plan) -> album.heading() to plan.first.words }, joins.flatMap { it.second.second })
        }
        HealthCheck.NoCover -> {
            val steps = coverSteps(serverIds)
            FixPreview(steps.size, steps.map { it.title to null }, steps)
        }
        HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> {
            val steps = fillSteps(check, serverIds)
            val lines = steps.map { step ->
                step.title to (step as FixStep.Retag).tags.map { (tag, value) -> TagChange(tag, null, value).words() }.joinToString(", ")
            }
            FixPreview(steps.size, lines, steps)
        }
        // Looked up first, then reviewed; and asked through Find higher quality.
        HealthCheck.NoTrackNumber, HealthCheck.NoLength -> FixPreview(0, emptyList(), emptyList())
    }
}
