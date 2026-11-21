package workbench2.oemlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android-process IPC test, NOT a ColorOS OEM task acceptance test. */
@RunWith(AndroidJUnit4.class)
public final class BridgeIpcInstrumentedTest {
    @Test public void readOnlyMessengerReportsNoMutationAndActualGrant() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch response = new CountDownLatch(1);
        AtomicReference<android.os.Bundle> result = new AtomicReference<>();
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == OemBridgeService.MSG_STATUS_REPLY) {
                result.set(msg.getData());
                response.countDown();
                return true;
            }
            return false;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Message query = Message.obtain(null, OemBridgeService.MSG_READ_STATUS);
                query.replyTo = receiver;
                try { new Messenger(binder).send(query); }
                catch (RemoteException e) { response.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                response.countDown();
            }
        };
        boolean bound = context.bindService(new Intent(context, OemBridgeService.class),
                connection, Context.BIND_AUTO_CREATE);
        assertTrue("Private bridge should bind from same app", bound);
        try {
            assertTrue("No reply from independent bridge process", response.await(15, TimeUnit.SECONDS));
            android.os.Bundle reply = result.get();
            assertNotNull("No valid status reply", reply);
            assertFalse("OEM task mutation must remain forbidden",
                    reply.getBoolean(OemBridgeService.EXTRA_MUTATION, true));
            boolean actual = context.getPackageManager().checkPermission(
                    "com.oplus.permission.safe.WINDOW", context.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
            assertEquals("Bridge and UI permission observations differ", actual,
                    reply.getBoolean(OemBridgeService.EXTRA_GRANTED, !actual));
            assertNotNull(reply.getString(OemBridgeService.EXTRA_PHASE));
        } finally {
            context.unbindService(connection);
        }
    }
}
