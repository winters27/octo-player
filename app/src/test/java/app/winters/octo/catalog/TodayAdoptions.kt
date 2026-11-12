package app.winters.octo.catalog

import androidx.room.withTransaction

// The one thing a save does that today's swap never did (TodaySwap.kt): a
// find adopted as a library song follows that song when the merge renames
// it, as queued songs do, to the song its source row became. An adoption
// whose song went with no source row to follow stays as it is. Frozen with
// TodaySwap; never edited to follow the app.
internal const val TODAY_ADOPTIONS_SQL = """
    UPDATE online_song SET adoptedId = (SELECT s.mergedId FROM source_track s WHERE s.id = online_song.adoptedId)
    WHERE adoptedId NOT IN (SELECT id FROM track)
      AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = online_song.adoptedId)
"""

// After today's swap (never after a skipped merge, which renames nothing):
// answers how many adoptions moved.
internal suspend fun todayAdoptions(db: CatalogDatabase): Int = db.withTransaction {
    db.openHelper.writableDatabase.compileStatement(TODAY_ADOPTIONS_SQL).use { it.executeUpdateDelete() }
}
