package app.winters.octo.ui.library.health.today

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.onServer
import app.winters.octo.health.AlbumJoin
import app.winters.octo.health.DuplicateFix
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import app.winters.octo.health.TagChange
import app.winters.octo.health.albumJoin
import app.winters.octo.health.copyName
import app.winters.octo.health.duplicateFix
import app.winters.octo.health.fillsFromAlbum
import app.winters.octo.health.heading
import app.winters.octo.health.steps
import app.winters.octo.health.summary
import app.winters.octo.health.words
import app.winters.octo.ui.library.health.ServerCopyHealth
import app.winters.octo.ui.library.health.SourceCopyHealth
import app.winters.octo.ui.library.health.TrackHealth

// A labeled pin: ui/library/health/HealthFixPlans.kt's Fix all preview and
// PhoneHealth.lines as they were at c2208eaa, over today's report and what
// the view model held whole (every song, the fields the checks read, each
// song's id on the server), with every fix offered. Frozen as the measure
// the plans and pages made from the report's tables must match. Never
// edited to follow the app.
data class TodayPreview(val count: Int, val lines: List<Pair<String, String?>>, val steps: List<FixStep>)

// A line of a check's page: a heading's words, or a song and its note.
sealed interface TodayLine {
    data class Heading(val title: String, val detail: String) : TodayLine
    data class Song(val id: String, val note: String?) : TodayLine
}

fun lines(health: TodayHealth, check: HealthCheck): List<TodayLine> = when (check) {
    HealthCheck.Duplicates -> health.report.duplicates.flatMapIndexed { i, group ->
        val words = health.copyGroups[i]
        listOf(TodayLine.Heading(words.heading(SourceCopyHealth), words.summary(SourceCopyHealth))) + group.copies.map { track ->
            TodayLine.Song(track.id, listOfNotNull(health.quality[track.id], track.album.ifBlank { null }).joinToString(" • ").ifEmpty { null })
        }
    }
    HealthCheck.SplitAlbums -> health.report.splitAlbums.flatMap { album ->
        listOf(TodayLine.Heading(album.heading(), album.summary())) + album.parts.flatMap { part -> part.songs.map { TodayLine.Song(it.id, null) } }
    }
    else -> health.report.songs(check).map { TodayLine.Song(it.id, null) }
}

private fun duplicatePlan(health: TodayHealth, index: Int, source: String?): DuplicateFix<SourceTrackEntity>? {
    val group = health.copyGroups.getOrNull(index) ?: return null
    if (source == null || group.best.sourceId != source) return null
    return duplicateFix(group, ServerCopyHealth, group.best)
}

fun joinPlan(health: TodayHealth, index: Int, fields: TrackHealth, serverIds: Map<String, String>): Pair<AlbumJoin<TrackEntity>, List<FixStep>>? {
    val album = health.report.splitAlbums.getOrNull(index) ?: return null
    val lead = album.parts[0].songs.firstOrNull { it.id in serverIds } ?: return null
    val join = albumJoin(album).copy(lead = lead)
    val steps = join.steps(fields).onServer(serverIds)
    return if (steps.isEmpty()) null else join to steps
}

fun fixAllPreview(
    health: TodayHealth,
    tracks: List<TrackEntity>,
    fields: TrackHealth,
    check: HealthCheck,
    edit: Boolean,
    source: String?,
    serverIds: Map<String, String>,
): TodayPreview = when (check) {
    HealthCheck.Duplicates -> {
        val plans = health.copyGroups.indices.mapNotNull { index ->
            val plan = duplicatePlan(health, index, source) ?: return@mapNotNull null
            plan to plan.steps(ServerCopyHealth, if (edit) plan.fills else emptyList())
        }
        val lines = plans.map { (plan, steps) ->
            val fills = steps.filterIsInstance<FixStep.Retag>().flatMap { it.tags.map { (tag, value) -> TagChange(tag, null, value).words() } }
            val removes = "Removes " + plan.remove.joinToString(", ") { copyName(it, ServerCopyHealth) } + "."
            val detail = if (fills.isEmpty()) removes else "$removes Fills in ${fills.joinToString(", ")}."
            "Keeps ${copyName(plan.keep, ServerCopyHealth)}" to detail
        }
        TodayPreview(plans.size, lines, plans.flatMap { it.second })
    }
    HealthCheck.SplitAlbums -> {
        val joins = health.report.splitAlbums.indices.mapNotNull { index -> joinPlan(health, index, fields, serverIds)?.let { health.report.splitAlbums[index] to it } }
        TodayPreview(joins.size, joins.map { (album, plan) -> album.heading() to plan.first.words }, joins.flatMap { it.second.second })
    }
    HealthCheck.NoCover -> {
        val steps = health.report.missing[HealthTag.Cover].orEmpty().map { FixStep.AddCover(it.id, it.title) }.onServer(serverIds)
        TodayPreview(steps.size, steps.map { it.title to null }, steps)
    }
    HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> {
        val tag = check.tag!!
        val steps = fillsFromAlbum(health.report.missing[tag].orEmpty(), tracks, tag, fields)
            .map { (song, change) -> FixStep.Retag(song.id, song.title, mapOf(change.tag to change.value)) }
            .onServer(serverIds)
        val lines = steps.map { step -> step.title to (step as FixStep.Retag).tags.map { (t, value) -> TagChange(t, null, value).words() }.joinToString(", ") }
        TodayPreview(steps.size, lines, steps)
    }
    HealthCheck.NoTrackNumber, HealthCheck.NoLength -> TodayPreview(0, emptyList(), emptyList())
}

// The songs one bulk look-up took: the first the server has, at most 25.
fun lookupBatch(songs: List<TrackEntity>, serverIds: Map<String, String>, limit: Int = 25): List<Pair<TrackEntity, String>> =
    songs.mapNotNull { song -> serverIds[song.id]?.let { song to it } }.distinctBy { it.second }.take(limit)
