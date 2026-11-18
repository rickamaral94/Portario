package br.com.amaral.portario

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity
data class CatalogSeed(@PrimaryKey val id: Int = 1, val json: String)

@Entity
data class Track(
    @PrimaryKey val id: String,
    val repository: String,
    val channel: String,
    val filter: String = "",
    val pinnedTag: String? = null,
    val favorite: Boolean = false,
    val assetName: String = "",
    val packageId: String = "",
    val installedTag: String = "",
    val installedPublishedAt: Long = 0,
    val installedVersionCode: Long = -1,
    val installedAssetId: Long = -1,
    val notifiedTag: String = "",
    val followedRelease: String = "",
    val preferGuide: Boolean = false
)

@Entity
data class ReleaseCache(
    @PrimaryKey val endpoint: String,
    val json: String,
    val etag: String?,
    val checkedAt: Long
)

@Entity
data class Transfer(
    @PrimaryKey val variant: String,
    val id: Long,
    val tag: String,
    val publishedAt: Long,
    val asset: String,
    val bytes: Long,
    val digest: String?,
    val packageId: String,
    val assetId: Long,
    val fingerprint: String,
    val status: String = "downloading",
    val message: String = ""
)

@Dao
interface PortarioDao {
    @Query("SELECT * FROM CatalogSeed WHERE id=1") suspend fun catalog(): CatalogSeed?
    @Upsert suspend fun save(value: CatalogSeed)
    @Query("SELECT * FROM Track ORDER BY favorite DESC, repository") fun tracks(): Flow<List<Track>>
    @Query("SELECT * FROM Track") suspend fun trackedNow(): List<Track>
    @Query("SELECT * FROM Track WHERE id=:id") suspend fun track(id: String): Track?
    @Upsert suspend fun save(value: Track)
    @Query("DELETE FROM Track WHERE id=:id") suspend fun deleteTrack(id: String)
    @Query("UPDATE Track SET notifiedTag=:tag WHERE id=:id") suspend fun markNotified(id: String, tag: String)
    @Query("UPDATE Track SET followedRelease=:fingerprint WHERE id=:id AND followedRelease=''")
    suspend fun setInitialRelease(id: String, fingerprint: String)
    @Query("UPDATE Track SET channel=:channel, filter=:filter, pinnedTag=:pinned, assetName=:asset, followedRelease=:baseline, notifiedTag=:baseline WHERE id=:id")
    suspend fun configure(id: String, channel: String, filter: String, pinned: String?, asset: String, baseline: String)
    @Query("UPDATE Track SET favorite=NOT favorite WHERE id=:id")
    suspend fun toggleFavorite(id: String)
    @Query("UPDATE Track SET assetName=:asset, notifiedTag=:fingerprint WHERE id=:id")
    suspend fun selectAsset(id: String, asset: String, fingerprint: String)
    @Query("UPDATE Track SET packageId=:pkg, installedVersionCode=:version, installedTag=:tag, installedPublishedAt=:time, installedAssetId=:assetId, notifiedTag=:fingerprint WHERE id=:id")
    suspend fun installed(id: String, pkg: String, version: Long, tag: String, time: Long, assetId: Long, fingerprint: String)
    @Query("SELECT * FROM ReleaseCache") fun caches(): Flow<List<ReleaseCache>>
    @Query("SELECT * FROM ReleaseCache WHERE endpoint=:endpoint") suspend fun cache(endpoint: String): ReleaseCache?
    @Upsert suspend fun save(value: ReleaseCache)
    @Query("SELECT * FROM Transfer") fun transfers(): Flow<List<Transfer>>
    @Query("SELECT * FROM Transfer WHERE variant=:variant") suspend fun transfer(variant: String): Transfer?
    @Query("SELECT * FROM Transfer WHERE id=:id") suspend fun transfer(id: Long): Transfer?
    @Query("DELETE FROM Transfer WHERE variant=:variant AND id=:id") suspend fun deleteTransfer(variant: String, id: Long)
    @Query("UPDATE Transfer SET status=:status, message=:message WHERE variant=:variant AND id=:id")
    suspend fun updateTransfer(variant: String, id: Long, status: String, message: String)
    @Query("UPDATE Transfer SET status=:status, message=:message WHERE variant=:variant AND id=:id AND status='downloading'")
    suspend fun finishDownload(variant: String, id: Long, status: String, message: String)
    @Upsert suspend fun save(value: Transfer)
}

@Database(entities = [CatalogSeed::class, Track::class, ReleaseCache::class, Transfer::class], version = 1, exportSchema = false)
abstract class PortarioDatabase : RoomDatabase() {
    abstract fun dao(): PortarioDao
}

private val Context.preferences by preferencesDataStore("settings")
private val periodic = booleanPreferencesKey("periodic_update_checks")

class PortarioApp : Application() {
    val database by lazy { Room.databaseBuilder(this, PortarioDatabase::class.java, "portario.db").build() }
    val dao get() = database.dao()
    val releases by lazy { ReleaseRepository(dao) }
    val downloads by lazy { PortarioDownloads(this) }
    val checksEnabled get() = preferences.data.map { it[periodic] ?: false }

    suspend fun setChecksEnabled(enabled: Boolean) {
        preferences.edit { it[periodic] = enabled }
        UpdateWorker.schedule(this, enabled)
    }

    suspend fun loadCatalog(): List<Game> {
        val embedded = assets.open("catalog.json").bufferedReader().use { it.readText() }
        val saved = dao.catalog()?.json
        if (saved != embedded) dao.save(CatalogSeed(json = embedded))
        return org.json.JSONObject(embedded).getJSONArray("games").objects().map(Game::from)
    }
}
