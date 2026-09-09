package com.rutv.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.rutv.data.local.AppDatabase
import com.rutv.data.model.Channel
import com.rutv.data.remote.PlaylistLoader
import com.rutv.data.remote.PlaylistParser
import com.rutv.domain.usecase.LoadPlaylistUseCase
import com.rutv.util.Result
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PlaylistPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var preferences: PreferencesRepository
    private lateinit var repository: ChannelRepositoryImpl
    private lateinit var load: LoadPlaylistUseCase
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttp://example.com/live\n"

    @Before fun setup() {
        val access = PlaylistAccess()
        preferences = PreferencesRepository(context, access)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = ChannelRepositoryImpl(db.channelDao(), preferences)
        load = LoadPlaylistUseCase(repository, preferences, PlaylistLoader(DefaultHttpDataSource.Factory()), PlaylistParser(), access)
    }
    @After fun close() { db.close() }

    @Test fun failedReloadPreservesCommittedSnapshotAndPreferences() = runBlocking {
        preferences.savePlaylistFromFile(playlist, "test")
        assertTrue(load.reload() is Result.Success)
        repository.toggleFavorite("http://example.com/live")
        repository.updateAspectRatio("http://example.com/live", 3)
        val before = (repository.getSnapshot() as Result.Success).data
        preferences.savePlaylistFromFile("not a playlist", "bad")
        assertTrue(load.reload() is Result.Error)
        assertEquals(before, (repository.getSnapshot() as Result.Success).data)
        preferences.savePlaylistFromFile(playlist, "test")
        val restored = (load.reload() as Result.Success).data.single()
        assertTrue(restored.isFavorite)
        assertEquals(3, restored.resizeMode.intValue)
    }

    @Test fun togglesAndRefreshKeepRoomAuthoritative() = runBlocking {
        preferences.savePlaylistFromFile(playlist, "test")
        load.reload()
        coroutineScope {
            repeat(10) { launch(Dispatchers.IO) { repository.toggleFavorite("http://example.com/live") } }
        }
        assertFalse((repository.getAllChannels() as Result.Success).data.single().isFavorite)
        // A stale backup must not resurrect a favorite when Room has current rows.
        preferences.replaceFavorites(setOf("http://example.com/live"), setOf("one"))
        val refreshed = (load.reload() as Result.Success).data.single()
        assertFalse(refreshed.isFavorite)
    }

    @Test fun sourceSwitchCannotAcceptPreviousSnapshotAsUrlCache() = runBlocking {
        preferences.savePlaylistFromFile(playlist, "test")
        load.reload()
        preferences.savePlaylistFromUrl("invalid-protocol://unreachable")
        assertTrue(load(skipNetworkIfCacheAvailable = true) is Result.Error)
        assertEquals(1, (repository.getAllChannels() as Result.Success).data.size)
    }

    @Test fun migrationFromThreePreservesRowsAndLeavesSourceUnverified() {
        val name = "migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE TABLE channels (url TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, logo TEXT NOT NULL, `group` TEXT NOT NULL, groupsJson TEXT NOT NULL, tvgId TEXT NOT NULL, catchupDays INTEGER NOT NULL, catchupSource TEXT NOT NULL, isFavorite INTEGER NOT NULL, aspectRatio INTEGER NOT NULL, position INTEGER NOT NULL)")
                    database.execSQL("INSERT INTO channels VALUES ('url', 'title', '', '', '[]', 'one', 0, '', 1, 2, 0)")
                }
                override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertTrue(migrated.channelDao().getAllChannels().single().isFavorite)
                assertNull(migrated.channelDao().getSnapshotIdentity())
            }
            assertEquals(4, migrated.openHelper.writableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
