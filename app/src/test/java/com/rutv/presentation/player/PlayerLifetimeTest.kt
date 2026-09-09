package com.rutv.presentation.player

import android.app.Application
import androidx.media3.datasource.DefaultHttpDataSource
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.repository.PlaylistAccess
import com.rutv.data.repository.PreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerLifetimeTest {
    @Test fun releaseSettlesQueuedAndNewDvrCommands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = RuntimeEnvironment.getApplication()
        val manager = PlayerManager(context, PreferencesRepository(context, PlaylistAccess()), DefaultHttpDataSource.Factory())
        val channel = Channel("http://example.com/live", "test")
        val program = EpgProgram("one", "", "", "test", startTimeMillis = 100, stopTimeMillis = 200)
        try {
            manager.prepare()
            // Enqueue before the main-loop command processor has been dispatched.
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                manager.playProgramDvr(channel, program, ProgramDvrMode.ARCHIVE_PROGRAM, 0, false)
            }
            manager.release()
            assertFalse(queued.await())
            assertFalse(manager.playProgramDvr(channel, program, ProgramDvrMode.ARCHIVE_PROGRAM, 0, false))
            manager.prepare()
            manager.release()
            assertNull(manager.getPlayer())
        } finally { manager.release(); Dispatchers.resetMain() }
    }
}
