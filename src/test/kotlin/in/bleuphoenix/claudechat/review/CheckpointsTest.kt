package `in`.bleuphoenix.claudechat.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckpointsTest {

    private val checkpoints = Checkpoints().apply {
        startTurn("m1")
        record("m1", "/p/A.kt", "a0")
        record("m1", "/p/A.kt", "a1") // a later edit in the same turn doesn't move the checkpoint
        startTurn("m2")
        startTurn("m3")
        record("m3", "/p/A.kt", "a2")
        record("m3", "/p/New.kt", null)
        record("m3", "/p/B.kt", "b0")
    }

    @Test
    fun `restoring to a message undoes that turn and every later one`() {
        assertEquals(mapOf("/p/A.kt" to "a0", "/p/New.kt" to null, "/p/B.kt" to "b0"), checkpoints.restoreTo("m1"))
        assertEquals(mapOf("/p/A.kt" to "a2", "/p/New.kt" to null, "/p/B.kt" to "b0"), checkpoints.restoreTo("m2"))
    }

    @Test
    fun `a message is restorable while it or a later turn changed files`() {
        assertTrue(checkpoints.hasChangesFrom("m1"))
        assertTrue("m2 changed nothing itself, but m3 did", checkpoints.hasChangesFrom("m2"))
        assertFalse(checkpoints.hasChangesFrom("unknown"))
    }

    @Test
    fun `restored turns are forgotten`() {
        checkpoints.dropFrom("m2")
        assertFalse(checkpoints.hasChangesFrom("m2"))
        assertEquals(mapOf("/p/A.kt" to "a0"), checkpoints.restoreTo("m1"))
    }
}
