package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class StagePromotedRecoverySnapshotTest {
    private data class Task(val id: Int, val user: Int = 0, val pkg: String = "expected", val display: Int = 0)
    private fun select(id:Int?, query:()->List<Task>) =
        StagePromotedRecoverySnapshot.select(query,id,{it.id},{it.user==0 && it.pkg=="expected"},{it.display==0})

    @Test fun eligibleForegroundUsesOneFreshList() {
        var reads=0
        val expected=Task(22)
        val r=select(22){reads++;listOf(Task(11,display=6),expected,Task(33))}
        assertEquals(expected,r.task);assertTrue(r.isForeground);assertEquals(1,reads)
    }
    @Test fun otherForegroundDoesNotAuthorizeRecovery() {
        val r=select(22){listOf(Task(33),Task(22))}
        assertNotNull(r.task);assertFalse(r.isForeground)
    }
    @Test fun identityAndDisplayMismatchCannotAuthorizeRecovery() {
        for(task in listOf(Task(22,user=10),Task(22,pkg="other"),Task(22,display=6))){
            val r=select(22){listOf(task)}
            assertNull(r.task);assertFalse(r.isForeground)
        }
    }
    @Test fun absentOrEmptyDoesNotAuthorizeRecovery() {
        for(tasks in listOf(emptyList(),listOf(Task(33)))){
            val r=select(22){tasks}
            assertNull(r.task);assertFalse(r.isForeground)
        }
        val absent=select(null){error("missing identity must not query")}
        assertNull(absent.task);assertFalse(absent.isForeground)
    }
    @Test fun nextRecoveryUsesNewObservationNotCachedList() {
        var reads=0
        val query={reads++;if(reads==1)listOf(Task(22)) else listOf(Task(33),Task(22))}
        assertTrue(select(22,query).isForeground)
        assertFalse(select(22,query).isForeground)
        assertEquals(2,reads)
    }
}
