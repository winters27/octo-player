package app.winters.octo.ui.library.health.today

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.HealthReport
import app.winters.octo.health.findDuplicates
import app.winters.octo.health.findMissingTags
import app.winters.octo.health.findSplitAlbums
import app.winters.octo.health.qualityText
import app.winters.octo.ui.library.health.SourceCopyHealth
import app.winters.octo.ui.library.health.TrackHealth

// A labeled pin: ui/library/health/PhoneHealth.kt's phoneHealth as it was
// at c2208eaa, given what HealthViewModel read whole (every song in library
// order, every album, every source's copies in the library in (source,
// order)), frozen as the measure the streamed build must match. The shared
// checks it calls are the desktop's, which their own tests pin. Never
// edited to follow the app.
class TodayHealth(
    val report: HealthReport<TrackEntity>,
    val quality: Map<String, String>,
    val copyGroups: List<DuplicateGroup<SourceTrackEntity>>,
)

fun phoneHealth(tracks: List<TrackEntity>, albums: List<AlbumEntity>, copies: List<SourceTrackEntity>): TodayHealth {
    val byId = tracks.associateBy { it.id }
    val fields = TrackHealth(albums.associate { it.id to it.artist }, copies.filter { it.mergedId.isNotEmpty() }.associateBy { it.mergedId })
    val groups = ArrayList<DuplicateGroup<TrackEntity>>()
    val sourceGroups = ArrayList<DuplicateGroup<SourceTrackEntity>>()
    val quality = HashMap<String, String>()
    for (group in findDuplicates(copies, SourceCopyHealth)) {
        val mapped = group.copies.mapNotNull { copy -> byId[copy.mergedId]?.also { quality[it.id] = qualityText(copy, SourceCopyHealth) } }
            .distinctBy { it.id }
        if (mapped.size < 2) continue
        groups += DuplicateGroup(mapped, group.basis, group.bestReason)
        sourceGroups += group
    }
    val report = HealthReport(
        checked = tracks.size,
        duplicates = groups,
        splitAlbums = findSplitAlbums(tracks, fields),
        noLength = tracks.filter { fields.seconds(it) <= 0 },
        missing = findMissingTags(tracks, fields),
    )
    return TodayHealth(report, quality, sourceGroups)
}
