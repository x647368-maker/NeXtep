package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test
class StageQuickBarEnablePolicyTest {
 @Test fun disabledMeansNoToolbarAndNoReopenHandle() {
  for (expanded in listOf(true,false)) {
   assertFalse(StageQuickBarEnablePolicy.showBar(true,false,expanded))
   assertFalse(StageQuickBarEnablePolicy.showHandle(true,false,expanded))
  }
 }
 @Test fun enabledHasExactlyOneEntry() {
  assertTrue(StageQuickBarEnablePolicy.showHandle(true,true,false))
  assertFalse(StageQuickBarEnablePolicy.showBar(true,true,false))
  assertTrue(StageQuickBarEnablePolicy.showBar(true,true,true))
  assertFalse(StageQuickBarEnablePolicy.showHandle(true,true,true))
 }
 @Test fun unavailableInputDoesNotExposeChrome() {
  assertFalse(StageQuickBarEnablePolicy.showBar(false,true,true))
  assertFalse(StageQuickBarEnablePolicy.showHandle(false,true,false))
 }
}
