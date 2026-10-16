package top.niunaijun.blackbox.fake.frameworks;

import android.content.Intent;
import android.os.RemoteException;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.am.IBActivityManagerService;
import top.niunaijun.blackbox.entity.AppConfig;

/**
 * Created by Milk on 4/14/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityManager {
    private static final BActivityManager sActivityManager = new BActivityManager();
    private IBActivityManagerService mService;

    public static BActivityManager get() {
        return sActivityManager;
    }

    public void startActivity(Intent intent, int userId) {
        try {
            getService().startActivity(intent, userId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Spawn (and bind + dump) the guest process directly, without launching a stub Activity.
     * The guest-side {@code initProcess} performs the whole DEX dump synchronously while the
     * init-process provider call is in flight, so the proxy process cannot be torn down
     * mid-dump on Android 13+.
     */
    public AppConfig initProcess(String packageName, String processName, int userId) {
        try {
            return getService().initProcess(packageName, processName, userId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean dumpApp(String packageName, String processName, int userId) {
        try {
            return getService().dumpApp(packageName, processName, userId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private IBActivityManagerService getService() {
        if (mService != null && mService.asBinder().isBinderAlive()) {
            return mService;
        }
        mService = IBActivityManagerService.Stub.asInterface(BlackBoxCore.get().getService(ServiceManager.ACTIVITY_MANAGER));
        return getService();
    }
}
