package io.github.x647368maker.atomicworkbench.task
import org.junit.Assert.*
import org.junit.Test
class FullscreenBoundsPolicyTest {
    @Test fun sameTaskAndContainerDoNotClearBoundsAgain() {
        val p=FullscreenBoundsPolicy();val token=Any()
        assertTrue(p.needsClear(1,token,false));p.markCleared(1,token)
        repeat(10){ assertFalse(p.needsClear(1,token,false)) }
    }
    @Test fun replacementContainerOrChangedNativeBoundsCanClearSafely() {
        val p=FullscreenBoundsPolicy();val token=Any();p.markCleared(1,token)
        assertTrue(p.needsClear(1,Any(),false));assertTrue(p.needsClear(1,token,true));assertTrue(p.needsClear(2,token,false))
    }
    @Test fun failedOrUnknownContainerIsNeverCachedAsSuccess() {
        val p=FullscreenBoundsPolicy();p.markCleared(1,null);assertTrue(p.needsClear(1,null,false))
    }
}
