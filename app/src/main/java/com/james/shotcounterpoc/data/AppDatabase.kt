package com.james.shotcounterpoc.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "shot_series")
data class ShotSeriesEntity(
    @PrimaryKey val id: String,
    val name: String,
    val recordedRoundCount: Int,
    val createdAtMillis: Long
)

@Entity(
    tableName = "shot_events",
    foreignKeys = [ForeignKey(
        entity = ShotSeriesEntity::class,
        parentColumns = ["id"],
        childColumns = ["shotSeriesId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("shotSeriesId")]
)
data class ShotEventEntity(
    @PrimaryKey val id: String,
    val shotSeriesId: String,
    val detectedAtMillis: Long,
    val confidence: Float,
    val peakDb: Float,
    @ColumnInfo(defaultValue = "NULL") val audioClipPath: String? = null
)

data class ShotSeriesWithEvents(
    @Embedded val series: ShotSeriesEntity,
    @Relation(parentColumn = "id", entityColumn = "shotSeriesId")
    val shots: List<ShotEventEntity>
)

@Dao
interface ShotSeriesDao {
    @Transaction
    @Query("SELECT * FROM shot_series ORDER BY createdAtMillis DESC")
    suspend fun getAllWithEvents(): List<ShotSeriesWithEvents>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: ShotSeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<ShotEventEntity>)

    @Query("DELETE FROM shot_series WHERE id = :id")
    suspend fun deleteSeries(id: String)

    @Query("SELECT audioClipPath FROM shot_events WHERE shotSeriesId = :seriesId AND audioClipPath IS NOT NULL")
    suspend fun getClipPathsForSeries(seriesId: String): List<String>

    @Query("SELECT audioClipPath FROM shot_events WHERE audioClipPath IS NOT NULL")
    suspend fun getAllClipPaths(): List<String>

    @Query("DELETE FROM shot_series")
    suspend fun deleteAll()

    @Query("UPDATE shot_events SET audioClipPath = :path WHERE id = :eventId")
    suspend fun updateClipPath(eventId: String, path: String)
}

@Database(
    entities = [ShotSeriesEntity::class, ShotEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ShotSeriesDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shot_counter_db"
                ).build().also { INSTANCE = it }
            }
    }
}
