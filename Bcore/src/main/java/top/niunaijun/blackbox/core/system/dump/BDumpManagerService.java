package top.niunaijun.blackbox.core.system.dump;

import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import top.niunaijun.blackbox.entity.dump.DumpResult;

/**
 * Created by Milk on 2021/5/22.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BDumpManagerService extends IBDumpManagerService.Stub {
    private static final String TAG = "BDumpManagerService";
    private static final BDumpManagerService sService = new BDumpManagerService();

    // RemoteCallbackList is thread-safe for register/unregister and unlinks dead binders itself.
    private final RemoteCallbackList<IBDumpMonitor> mMonitors = new RemoteCallbackList<>();
    // beginBroadcast()/finishBroadcast() must not be nested or run concurrently.
    private final Object mBroadcastLock = new Object();

    public static BDumpManagerService get() {
        return sService;
    }

    @Override
    public void registerMonitor(IBinder monitor) {
        IBDumpMonitor callback = IBDumpMonitor.Stub.asInterface(monitor);
        if (callback == null) {
            return;
        }
        // Returns false (and does not register) if the binder is already dead.
        if (!mMonitors.register(callback)) {
            Log.w(TAG, "registerMonitor: monitor already dead, ignored");
        }
    }

    @Override
    public void unregisterMonitor(IBinder monitor) {
        IBDumpMonitor callback = IBDumpMonitor.Stub.asInterface(monitor);
        if (callback == null) {
            return;
        }
        mMonitors.unregister(callback);
    }

    @Override
    public void noticeMonitor(DumpResult result) throws RemoteException {
        synchronized (mBroadcastLock) {
            int n = mMonitors.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    try {
                        mMonitors.getBroadcastItem(i).onDump(result);
                    } catch (RemoteException | RuntimeException e) {
                        // One bad monitor must not stop delivery to the rest.
                        Log.w(TAG, "noticeMonitor: delivery to monitor " + i + " failed", e);
                    }
                }
            } finally {
                mMonitors.finishBroadcast();
            }
        }
    }
}
