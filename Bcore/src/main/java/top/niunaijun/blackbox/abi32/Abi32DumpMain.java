package top.niunaijun.blackbox.abi32;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.VMCore;

/**
 * Entry point of the 32-bit dump helper.
 *
 * <p>A 64-bit process cannot load 32-bit native code, so a guest that only ships
 * {@code lib/armeabi-v7a} (e.g. a packer whose decryptor is 32-bit only) cannot be dumped in the
 * normal 64-bit sandbox. Instead of shipping a second "32-bit" app, the host runs this class in a
 * 32-bit ART started from {@code /system/bin/app_process32} under the host's own UID
 * (see {@link Abi32Dumper}). It runs the guest standalone: no sandbox server, just the guest's
 * Application plus the same native IO-redirect, anti-kill and dump code the sandbox uses.
 *
 * <p>Protocol with the host: one request file (a marshalled {@link Abi32Request}); progress and
 * result are written to stdout as single lines: {@code PROGRESS <total> <current>},
 * {@code DONE <dexCount>} or {@code ERROR <message>}.
 */
public final class Abi32DumpMain {
    private static final String TAG = "BlackDex32";

    private Abi32DumpMain() {
    }

    public static void main(String[] args) {
        int code = 0;
        try {
            if (args.length < 1) throw new IllegalArgumentException("missing request file");
            run(new File(args[0]));
        } catch (Throwable t) {
            Log.e(TAG, "32-bit dump failed", t);
            emit("ERROR " + describe(t));
            code = 2;
        }
        System.out.flush();
        // Don't wait for guest threads (or let a guest shutdown hook) delay the result.
        Process.killProcess(Process.myPid());
        System.exit(code);
    }

    private static void run(File requestFile) throws Throwable {
        Abi32Request req = Abi32Request.read(requestFile);
        PackageInfo pkg = req.packageInfo;
        ApplicationInfo ai = pkg.applicationInfo;
        Log.i(TAG, "dumping " + ai.packageName + " in 32-bit runtime, is64=" + Process.is64Bit());

        // Guest SDKs often die on background threads (missing permissions, no real app record in
        // AMS). That must not take the process down before the dex is on disk.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                Log.w(TAG, "guest thread '" + t.getName() + "' threw (ignored)", e));

        // A bare app_process has no per-app compat config, so every platform behaviour change is
        // "enabled" — as if the guest targeted the newest SDK. That e.g. makes System.load() of
        // a packer's own extracted (writable) .so throw. Run with legacy behaviour instead, like
        // the old-target-SDK apps this path is for, and match the guest's target SDK in ART.
        relaxCompatChanges(ai.targetSdkVersion);

        Looper.prepareMainLooper();
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object activityThread = atClass.getMethod("systemMain").invoke(null);
        Context systemContext = (Context) atClass.getMethod("getSystemContext").invoke(activityThread);
        Context host = systemContext.createPackageContext(req.hostPackage,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        BlackBoxCore.attachStandalone(host);

        // systemMain() makes this process present itself as "android"; system services then
        // reject calls with "callingPackage does not match UID". Re-badge every context we hand
        // out (system context, framework Application, and the guest's own context before its
        // attachBaseContext runs) with the host package, which does match our UID.
        sHostPackage = req.hostPackage;
        fixIdentity(systemContext);
        Object frameworkApp = getField(atClass, activityThread, "mInitialApplication");
        if (frameworkApp instanceof Context) fixIdentity((Context) frameworkApp);
        setField(atClass, activityThread, "mInstrumentation", new IdentityInstrumentation());

        // The sandbox already pointed dataDir/nativeLibraryDir into our own storage; make sure the
        // runtime treats the guest as 32-bit and that its dirs exist.
        setField(ApplicationInfo.class, ai, "primaryCpuAbi", req.abi);
        ai.uid = Process.myUid();
        mkdirs(ai.dataDir);
        if (Build.VERSION.SDK_INT >= 24 && ai.deviceProtectedDataDir != null) {
            mkdirs(ai.deviceProtectedDataDir);
        }
        File outDir = new File(req.outDir);
        mkdirs(outDir.getAbsolutePath());

        VMCore.init(Build.VERSION.SDK_INT);
        // NOTE: never createPackageContext(guest) here — that caches a LoadedApk built from the
        // guest's REAL install (dataDir=/data/data/<pkg>), and getPackageInfoNoCheck() below would
        // then hand it back instead of one built from the sandbox ApplicationInfo.
        // Guest external storage (/sdcard/Android/data/<pkg>/...) goes to a per-guest dir of ours.
        final File guestExternalRoot = new File(host.getExternalFilesDir("virtual"), ai.packageName);
        Context redirectContext = new android.content.ContextWrapper(host) {
            @Override
            public File getExternalCacheDir() {
                File cache = new File(guestExternalRoot, "cache");
                //noinspection ResultOfMethodCallIgnored
                cache.mkdirs();
                return cache;
            }
        };
        IOCore.get().enableRedirect(redirectContext, ai, host.getExternalFilesDir(null) != null);

        // The guest may not be installed (APK file), and if it is, the real PM would hand back its
        // real paths. Answer the guest's own package queries from the sandbox PackageInfo.
        installPackageManagerProxy(atClass, pkg);

        Object compat = Class.forName("android.content.res.CompatibilityInfo")
                .getField("DEFAULT_COMPATIBILITY_INFO").get(null);
        Object loadedApk = findMethod(atClass, "getPackageInfoNoCheck", 2)
                .invoke(activityThread, ai, compat);

        // Packers reach into ActivityThread.mBoundApplication.info to swap the classloader /
        // Application, so give them a real bind record (systemMain() leaves it null).
        installBindData(atClass, activityThread, ai, loadedApk);

        if (req.hookDump) {
            VMCore.installHookDump(outDir.getAbsolutePath());
        }

        ClassLoader classLoader = (ClassLoader) loadedApk.getClass().getMethod("getClassLoader").invoke(loadedApk);
        Thread.currentThread().setContextClassLoader(classLoader);

        Application application = null;
        try {
            application = makeApplication(loadedApk);
        } catch (Throwable e) {
            Log.e(TAG, "makeApplication failed (continuing)", e);
        }
        if (application != null) {
            VMCore.sStandaloneApplication = application;
            setField(atClass, activityThread, "mInitialApplication", application);
            try {
                application.onCreate();
            } catch (Throwable e) {
                Log.w(TAG, "application.onCreate() threw (continuing)", e);
            }
            classLoader = application.getClassLoader();
        }

        // Same two-pass snapshot as the sandbox: packers often finish materialising the real dex
        // shortly after onCreate, sometimes on a background thread.
        sleep(900);
        dumpPass(classLoader, ai.packageName, pkg, outDir, req.fixCodeItem, application);
        sleep(1500);
        dumpPass(classLoader, ai.packageName, pkg, outDir, req.fixCodeItem, application);

        File[] files = outDir.listFiles((dir, name) -> name.endsWith(".dex"));
        int count = files == null ? 0 : files.length;
        Log.i(TAG, "dump done, files=" + count + " dir=" + outDir);
        if (count == 0) {
            emit("ERROR not found dex file");
        } else {
            emit("DONE " + count);
        }
    }

    private static void dumpPass(ClassLoader cl, String packageName, PackageInfo pkg, File outDir,
                                 boolean fixCodeItem, Application app) {
        try {
            VMCore.cookieDumpDex(cl, packageName, pkg, outDir, fixCodeItem, app,
                    (total, current) -> emit("PROGRESS " + total + " " + current));
        } catch (Throwable e) {
            Log.e(TAG, "cookieDumpDex pass failed", e);
        }
    }

    private static Application makeApplication(Object loadedApk) throws Throwable {
        Class<?> c = loadedApk.getClass();
        // API <=33: makeApplication(boolean, Instrumentation); API >=34: makeApplicationInner.
        for (String name : new String[]{"makeApplication", "makeApplicationInner"}) {
            Method m;
            try {
                m = c.getDeclaredMethod(name, boolean.class, Instrumentation.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            m.setAccessible(true);
            Object app = m.invoke(loadedApk, false, null);
            if (app != null) return (Application) app;
        }
        return null;
    }

    private static void installBindData(Class<?> atClass, Object activityThread, ApplicationInfo ai,
                                        Object loadedApk) {
        try {
            Class<?> bindClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor = bindClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object bind = ctor.newInstance();
            setField(bindClass, bind, "appInfo", ai);
            setField(bindClass, bind, "info", loadedApk);
            setField(bindClass, bind, "processName", ai.packageName);
            setField(atClass, activityThread, "mBoundApplication", bind);
        } catch (Throwable e) {
            Log.w(TAG, "could not install AppBindData", e);
        }
    }

    private static volatile String sHostPackage;

    private static void installPackageManagerProxy(Class<?> atClass, PackageInfo guest) {
        try {
            Method getPm = atClass.getDeclaredMethod("getPackageManager");
            getPm.setAccessible(true);
            final Object realPm = getPm.invoke(null);
            Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
            final String guestPkg = guest.packageName;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(ipm.getClassLoader(),
                    new Class<?>[]{ipm}, (p, m, a) -> {
                        String name = m.getName();
                        if (a != null && a.length > 0 && guestPkg.equals(a[0])) {
                            if ("getPackageInfo".equals(name)) {
                                return copyPackageInfo(guest);
                            }
                            if ("getApplicationInfo".equals(name)) {
                                return new ApplicationInfo(guest.applicationInfo);
                            }
                        }
                        try {
                            return m.invoke(realPm, a);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            Field f = atClass.getDeclaredField("sPackageManager");
            f.setAccessible(true);
            f.set(null, proxy);
        } catch (Throwable e) {
            Log.w(TAG, "could not install PackageManager proxy", e);
        }
    }

    private static PackageInfo copyPackageInfo(PackageInfo src) {
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            src.writeToParcel(p, 0);
            p.setDataPosition(0);
            return PackageInfo.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    private static void relaxCompatChanges(int guestTargetSdk) {
        try {
            Class<?> vmr = Class.forName("dalvik.system.VMRuntime");
            Object rt = vmr.getMethod("getRuntime").invoke(null);
            vmr.getMethod("setTargetSdkVersion", int.class).invoke(rt, guestTargetSdk);
        } catch (Throwable e) {
            Log.w(TAG, "setTargetSdkVersion failed", e);
        }
        try {
            Class<?> compat = Class.forName("android.compat.Compatibility");
            Class<?> delegate = Class.forName("android.compat.Compatibility$BehaviorChangeDelegate");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(delegate.getClassLoader(),
                    new Class<?>[]{delegate}, (p, m, a) -> {
                        if ("isChangeEnabled".equals(m.getName())) return false;
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("toString".equals(m.getName())) return "Abi32LegacyCompat";
                        return null;
                    });
            compat.getMethod("setBehaviorChangeDelegate", delegate).invoke(null, proxy);
        } catch (ClassNotFoundException e) {
            // Older Android: no delegate API, and none of the checks we care about exist.
        } catch (Throwable e) {
            Log.w(TAG, "could not relax compat changes", e);
        }
    }

    /** Instrumentation that re-badges the guest Application's context before attach. */
    private static final class IdentityInstrumentation extends Instrumentation {
        @Override
        public Application newApplication(ClassLoader cl, String className, Context context)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            fixIdentity(context);
            return super.newApplication(cl, className, context);
        }
    }

    /**
     * Point a ContextImpl's calling-package identity at the host (our UID's package) while
     * leaving getPackageName() alone, so the guest still sees its own package name.
     */
    static void fixIdentity(Context context) {
        String hostPkg = sHostPackage;
        if (context == null || hostPkg == null) return;
        Context c = context;
        for (int i = 0; i < 10 && c instanceof android.content.ContextWrapper; i++) {
            Context base = ((android.content.ContextWrapper) c).getBaseContext();
            if (base == null) break;
            c = base;
        }
        Class<?> impl = c.getClass();
        setFieldQuiet(impl, c, "mBasePackageName", hostPkg);
        setFieldQuiet(impl, c, "mOpPackageName", hostPkg); // API <= 30
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                android.content.AttributionSource cur = c.getAttributionSource();
                android.content.AttributionSource fixed =
                        new android.content.AttributionSource.Builder(Process.myUid())
                                .setPackageName(hostPkg)
                                .setAttributionTag(cur == null ? null : cur.getAttributionTag())
                                .build();
                setFieldQuiet(impl, c, "mAttributionSource", fixed);
            } catch (Throwable e) {
                Log.w(TAG, "fix attribution source failed", e);
            }
        }
    }

    private static Object getField(Class<?> c, Object target, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void setFieldQuiet(Class<?> c, Object target, String name, Object value) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable e) {
                return;
            }
        }
    }

    private static Method findMethod(Class<?> c, String name, int paramCount) throws NoSuchMethodException {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static void setField(Class<?> c, Object target, String name, Object value) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable e) {
            Log.w(TAG, "setField " + name + " failed", e);
        }
    }

    private static void mkdirs(String path) {
        if (path != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).mkdirs();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static synchronized void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static String describe(Throwable t) {
        while (t.getCause() != null && t.getCause() != t
                && (t instanceof java.lang.reflect.InvocationTargetException || t.getMessage() == null)) {
            t = t.getCause();
        }
        String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return msg.replace('\n', ' ');
    }
}
