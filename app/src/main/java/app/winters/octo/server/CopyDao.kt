package app.winters.octo.server

import androidx.room.Dao
import androidx.room.Query

// A server copy's working rows: what the server sent, and the source rows
// built from it.
@Dao
interface CopyDao {
    @Query("SELECT COUNT(*) FROM sync_song")
    suspend fun syncSongCount(): Int
}
