package app.winters.octo.listening

import androidx.room.Dao
import androidx.room.Query

// Keeping in step with the server in use: the rows ListeningStore keeps,
// and the reads after a copy.
@Dao
interface ListeningDao {
    @Query("SELECT EXISTS(SELECT 1 FROM listening_import WHERE id = 0)")
    suspend fun imported(): Boolean
}
