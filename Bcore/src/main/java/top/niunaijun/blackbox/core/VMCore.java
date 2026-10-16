package top.niunaijun.blackbox.core;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.DexUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;
import top.niunaijun.jnihook.MethodUtils;

import static top.niunaijun.blackbox.core.env.BEnvironment.EMPTY_JAR;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class VMCore {
    public static final String TAG = "VMCoreJava";

    static {
        new File("");
        System.loadLibrary("blackdex");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    private static native void cookieDumpDex(long cookie, String dir, boolean fixMethod);

    private static native void hookDumpDex(String dir);

    public static void cookieDumpDex(ClassLoader classLoader, String packageName) {
        // 1) Seed with the manifest's declared component classes (Activities/Services/Receivers/
        //    Providers). Loading e.g. the launcher Activity forces the packer to decrypt and load
        //    the REAL app dex that otherwise only materialises when the UI starts — we never
        //    launch an Activity in the dump flow, so this is how that code gets into memory.
        forceLoadComponents(classLoader, packageName);
        // 2) Now load every class name known across the (now-expanded) classloader chain, so
        //    extraction-style packers materialise each method body.
        forceLoadAllClasses(classLoader);

        // Collect DexFile cookies from EVERY classloader we can reach: the one captured at bind
        // time, the current thread's context classloader, and the live application's classloader.
        // Packers frequently install their decrypted dex into a brand-new DexClassLoader and set
        // it as the context/application classloader, so a single root misses it.
        final List<Long> cookies = new java.util.ArrayList<>();
        for (ClassLoader root : collectRootClassLoaders(classLoader)) {
            for (long c : DexFileCompat.getAllCookies(root)) {
                if (c != 0 && !cookies.contains(c)) cookies.add(c);
            }
        }
        if (cookies.isEmpty()) {
            cookies.addAll(DexFileCompat.getCookies(classLoader));
        }
        File file = new File(BlackBoxCore.get().getDexDumpDir(), packageName);

        DumpResult result = new DumpResult();
        result.dir = file.getAbsolutePath();
        result.packageName = packageName;
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(availableProcessors <= 0 ? 1 : availableProcessors);
        CountDownLatch countDownLatch = new CountDownLatch(cookies.size());
        AtomicInteger atomicInteger = new AtomicInteger(0);

        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
        for (int i = 0; i < cookies.size(); i++) {
            long cookie = cookies.get(i);
            if (cookie == 0) {
                countDownLatch.countDown();
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                continue;
            }
            FileUtils.mkdirs(file);
            if (atomicInteger.get() == 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            executorService.execute(() -> {
                cookieDumpDex(cookie, file.getAbsolutePath(), BlackBoxCore.get().isFixCodeItem());
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException ignored) {
        }
        File[] files = file.listFiles();
        if (files != null) {
            for (File dex : files) {
                if (dex.isFile() && dex.getAbsolutePath().endsWith(".dex")) {
                    DexUtils.fixDex(dex);
                }
            }
        }
    }

    /**
     * Best-effort "active loading": ask the classloader to load every class name it exposes.
     * For extraction/on-demand packers this triggers per-class decryption so the real code is
     * present in memory when we snapshot the dex. Runs quietly — missing/abstract classes and
     * initializer side effects are ignored.
     */
    /** The distinct classloaders worth scanning for the target's (possibly packer-loaded) dex. */
    private static java.util.List<ClassLoader> collectRootClassLoaders(ClassLoader passed) {
        java.util.List<ClassLoader> roots = new java.util.ArrayList<>();
        addRoot(roots, passed);
        try {
            addRoot(roots, Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
        }
        try {
            android.app.Application app = top.niunaijun.blackbox.app.BActivityThread.getApplication();
            if (app != null) {
                addRoot(roots, app.getClassLoader());
                addRoot(roots, app.getBaseContext() == null ? null : app.getBaseContext().getClassLoader());
            }
        } catch (Throwable ignored) {
        }
        return roots;
    }

    private static void addRoot(java.util.List<ClassLoader> roots, ClassLoader cl) {
        if (cl != null && !roots.contains(cl)) roots.add(cl);
    }

    /**
     * Load the app's declared component classes (from the manifest) to trigger the packer's
     * on-demand dex decryption. These are the entry points real app code hangs off of.
     */
    private static void forceLoadComponents(ClassLoader classLoader, String packageName) {
        try {
            int flags = android.content.pm.PackageManager.GET_ACTIVITIES
                    | android.content.pm.PackageManager.GET_SERVICES
                    | android.content.pm.PackageManager.GET_RECEIVERS
                    | android.content.pm.PackageManager.GET_PROVIDERS;
            android.content.pm.PackageInfo pi = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, flags,
                            top.niunaijun.blackbox.app.BActivityThread.getUserId());
            if (pi == null) return;
            java.util.List<String> names = new java.util.ArrayList<>();
            if (pi.activities != null)
                for (android.content.pm.ActivityInfo c : pi.activities) names.add(c.name);
            if (pi.services != null)
                for (android.content.pm.ServiceInfo c : pi.services) names.add(c.name);
            if (pi.receivers != null)
                for (android.content.pm.ActivityInfo c : pi.receivers) names.add(c.name);
            if (pi.providers != null)
                for (android.content.pm.ProviderInfo c : pi.providers) names.add(c.name);
            if (pi.applicationInfo != null) names.add(pi.applicationInfo.className);
            for (String name : names) {
                if (name == null) continue;
                try {
                    classLoader.loadClass(name);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceLoadAllClasses(ClassLoader classLoader) {
        try {
            List<String> names = DexFileCompat.getAllClassNames(classLoader);
            for (String name : names) {
                if (name == null) continue;
                try {
                    // loadClass (not Class.forName) — resolve without running static initializers.
                    classLoader.loadClass(name);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
//        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
//            return origCallingUid;
//        // 非用户应用
//        if (origCallingUid > Process.LAST_APPLICATION_UID)
//            return origCallingUid;
//
//        Log.d(TAG, "origCallingUid: " + origCallingUid + " => " + BClient.getBaseVUid());
//        return BClient.getBaseVUid();
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            DexFile dexFile = new DexFile(EMPTY_JAR);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }

    @Keep
    public static Object findMethod(String className, String methodName, String signature) {
        try {
            className = className.replace("/", ".");
            if (className.startsWith("L")) {
                className = className.substring(1);
            }
            if (className.endsWith(";")) {
                className = className.substring(0, className.length() - 1);
            }
            ClassLoader classLoader = BActivityThread.getApplication().getClassLoader();
            Class<?> aClass = Class.forName(className, false, classLoader);
            if ("<init>".equals(methodName)) {
                Constructor<?>[] constructors = aClass.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    String desc = MethodUtils.getDesc(constructor);
                    if (signature.equals(desc)) {
                        return constructor;
                    }
                }
            }

            try {
                Method[] declaredMethods = aClass.getDeclaredMethods();
                for (Method declaredMethod : declaredMethods) {
                    if (declaredMethod.getName().equals(methodName)) {
                        String desc = MethodUtils.getDesc(declaredMethod);
                        if (desc.equals(signature)) {
                            return declaredMethod;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    String desc = MethodUtils.getDesc(method);
                    if (desc.equals(signature)) {
                        return method;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
