package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class StageEarlySlotOutputPolicyTest {
    @Test fun onlySameProducerAndCanvasCanReuseEarlierMark(){
        val texture=Any();val surface=Any()
        val p=StageEarlySlotOutputPolicy
        assertTrue(p.allowEarlier(5,5,texture,texture,surface,surface,12,13,true))
        assertTrue(p.allowEarlier(5,5,texture,texture,surface,surface,12,12,true))
        assertFalse(p.allowEarlier(5,6,texture,texture,surface,surface,12,13,true))
        assertFalse(p.allowEarlier(5,5,texture,Any(),surface,surface,12,13,true))
        assertFalse(p.allowEarlier(5,5,texture,texture,surface,Any(),12,13,true))
        assertFalse(p.allowEarlier(5,5,texture,texture,surface,surface,12,13,false))
        assertFalse(p.allowEarlier(5,5,texture,texture,surface,surface,13,12,true))
    }
}
