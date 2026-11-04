package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Query

// The merge's working rows and records (WorkTables).
@Dao
interface WorkDao {
    @Query("SELECT * FROM merge_state WHERE id = 0")
    suspend fun mergeState(): MergeStateEntity?
}
