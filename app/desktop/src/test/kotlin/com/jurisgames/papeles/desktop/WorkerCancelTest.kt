package com.jurisgames.papeles.desktop

import androidx.compose.material3.SnackbarHostState
import com.jurisgames.papeles.ui.Worker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** APP-RULES 205: a long job has Cancel; a cancelled job's result is dropped (nothing saved), and the next job runs. */
class WorkerCancelTest {
    @Test fun aCancelledJobSavesNothingAndTheNextOneRuns() = runBlocking {
        val w = Worker(this, SnackbarHostState())
        var saved = 0
        w.run({ Thread.sleep(400); "slow" }) { saved++ }
        assertTrue(w.busy)
        w.cancel()
        assertFalse(w.busy, "Cancel gives the screen back at once")
        var next: String? = null
        w.run({ "quick" }) { next = it }
        repeat(100) { if (next == null) delay(20) }
        assertEquals("quick", next) // pinned: the job after a Cancel runs and its own result arrives
        delay(700) // the cancelled job has finished in the background by now
        assertEquals(0, saved, "the cancelled job's result was dropped")
        assertFalse(w.busy)
    }
}
