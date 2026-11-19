package workbench2.hostruntime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public final class HostMetadataTest {
    private static class Tracker {
        static int initialized;
        static int calls;
    }
    public static class Fixture {
        static { Tracker.initialized++; }
        public void init(String value, int id) { Tracker.calls++; }
        public void init(int id) { Tracker.calls++; }
        public void release() { Tracker.calls++; }
        private void resize() { Tracker.calls++; }
        public void unrelated() { Tracker.calls++; }
    }
    private static boolean gate(String p, String proc, boolean first, boolean ss,
            boolean systemApp, boolean uid, String model, int sdk, String fw) {
        return HostMetadata.matches(p, proc, first, ss, systemApp, uid, model, sdk, fw);
    }
    @Test public void onlyExactAuthorizedObservationTargetMatches() {
        String h = HostMetadata.HOST, m = HostMetadata.MODEL, f = HostMetadata.FIRMWARE;
        assertTrue(gate(h,h,true,false,true,true,m,37,f));
        assertFalse(gate("other",h,true,false,true,true,m,37,f));
        assertFalse(gate(h,h+":remote",true,false,true,true,m,37,f));
        assertFalse(gate(h,h,false,false,true,true,m,37,f));
        assertFalse(gate(h,h,true,true,true,true,m,37,f));
        assertFalse(gate(h,h,true,false,false,true,m,37,f));
        assertFalse(gate(h,h,true,false,true,false,m,37,f));
        assertFalse(gate(h,h,true,false,true,true,"other",37,f));
        assertFalse(gate(h,h,true,false,true,true,m,36,f));
        assertFalse(gate(h,h,true,false,true,true,m,37,f+"-OTA"));
    }
    @Test public void publicMetadataDoesNotInitializeOrInvokeFixture() {
        assertEquals(0, Tracker.initialized);
        List<String> values = HostMetadata.inspect(getClass().getClassLoader(), Fixture.class.getName());
        assertEquals(List.of("init(int):void:instance","init(java.lang.String,int):void:instance",
                "release():void:instance"), values);
        assertEquals(0, Tracker.initialized);
        assertEquals(0, Tracker.calls);
    }
    @Test public void unavailableMetadataDoesNotAdmitOrFabricateSymbols() {
        assertEquals(List.of("UNRESOLVED:ClassNotFoundException"),
            HostMetadata.inspect(getClass().getClassLoader(), "workbench2.hostruntime.NoSuchProbeType"));
    }
}
