package workbench2.hostruntime;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SessionFilterTest {
    @Test public void exactDualHostGate() {
        for(String p:List.of(HostMetadata.HOST,SessionFilter.LAUNCHER)){
            assertTrue(SessionFilter.exactHost(p,p,true,false,true,true,HostMetadata.MODEL,37,HostMetadata.FIRMWARE));
            assertFalse(SessionFilter.exactHost(p,p+":remote",true,false,true,true,HostMetadata.MODEL,37,HostMetadata.FIRMWARE));
            assertFalse(SessionFilter.exactHost(p,p,true,true,true,true,HostMetadata.MODEL,37,HostMetadata.FIRMWARE));
            assertFalse(SessionFilter.exactHost(p,p,true,false,true,true,HostMetadata.MODEL,37,HostMetadata.FIRMWARE+"new"));
        }
        assertFalse(SessionFilter.exactHost("android","android",true,false,true,true,HostMetadata.MODEL,37,HostMetadata.FIRMWARE));
    }
    @Test public void onlyOwnExplicitComponentMatches() {
        Intent own=new Intent().setComponent(new ComponentName(SessionFilter.PKG,SessionFilter.ACTIVITY));
        Bundle b=new Bundle();b.putParcelable("intent",own);
        assertTrue(SessionFilter.ownArgs(List.of(b)));
        assertTrue(SessionFilter.ownArgs(Arrays.asList(0,0f,0f,null,own,0,0)));
        assertFalse(SessionFilter.ownArgs(List.of(new Bundle())));
        assertFalse(SessionFilter.ownArgs(List.of(new Intent())));
        Bundle other=new Bundle();other.putParcelable("intent",new Intent().setComponent(new ComponentName("other","other.Main")));
        assertFalse(SessionFilter.ownArgs(List.of(other)));
    }
    @Test public void oneViewAndBoundedLifetime() {
        Object a=new Object(), b=new Object();
        SessionFilter f=new SessionFilter(100);
        assertFalse(f.start(null,101));assertTrue(f.start(a,101));
        assertFalse(f.start(b,102));assertFalse(f.finish(b,103));assertTrue(f.finish(a,104));
        assertFalse(f.finish(a,105));assertFalse(f.open(106));
        SessionFilter expired=new SessionFilter(100);
        assertFalse(expired.start(a,100+SessionFilter.WINDOW_MS));
        SessionFilter closing=new SessionFilter(100);assertTrue(closing.start(a,101));closing.clear();
        assertFalse(closing.finish(a,102));
    }
}
