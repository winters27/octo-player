package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.onServer
import app.winters.octo.health.AlbumJoin
import app.winters.octo.health.DuplicateFix
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthFields
import app.winters.octo.health.SplitAlbum
import app.winters.octo.health.TagChange
import app.winters.octo.health.albumJoin
import app.winters.octo.health.duplicateFix
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

// The plan for a set of copies, keeping `keep` (the best copy unless a
// person picked another). Null for a set that is not on the signed-in
// server's disk (`source`).
fun duplicatePlan(group: DuplicateGroup<SourceTrackEntity>, source: String?, keep: SourceTrackEntity? = null): DuplicateFix<SourceTrackEntity>? {
    if (source == null || group.best.sourceId != source) return null
    val kept = keep?.let { k -> group.copies.firstOrNull { it.id == k.id } } ?: group.best
    return duplicateFix(group, ServerCopyHealth, kept)
}

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

// The join of one split album, led by the first song of its first part
// (its largest) the server has, with its steps, each song by its id on the
// server (`serverIds`). Null when the server has nothing of it to move.
fun joinPlanOf(album: SplitAlbum<TrackEntity>, fields: TrackHealth, serverIds: Map<String, String>): Pair<AlbumJoin<TrackEntity>, List<FixStep>>? {
    val lead = album.parts[0].songs.firstOrNull { it.id in serverIds } ?: return null
    val join = albumJoin(album).copy(lead = lead)
    val steps = join.steps(fields).onServer(serverIds)
    return if (steps.isEmpty()) null else join to steps
}

// The step that writes the changes picked for one song, if any.
fun retagStep(serverId: String, title: String, changes: List<TagChange>): FixStep.Retag? =
    if (changes.isEmpty()) null else FixStep.Retag(serverId, title, changes.associate { it.tag to it.value })

// What a check's button for everything would do, shown before it runs: how
// many sets, albums or songs it fixes and the first page of their lines,
// one each; `plan` sends their steps.
data class FixPreview(val count: Int, val lines: List<Pair<String, String?>>, val plan: FixAllPlan)

// One set of copies or one split album Fix all fixes, and its steps; a
// song's steps alone have neither.
data class FixSet(val group: DuplicateGroup<SourceTrackEntity>? = null, val album: SplitAlbum<TrackEntity>? = null, val steps: List<FixStep>)
