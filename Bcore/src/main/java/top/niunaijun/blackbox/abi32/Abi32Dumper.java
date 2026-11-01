package top.niunaijun.blackbox.abi32;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.AbiUtils;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Host side of the 32-bit dump path: decides when a guest needs it, then runs
 * {@link Abi32DumpMain} in {@code /system/bin/app_process32} under our own UID and relays its
 * progress/result to the normal dump monitor, so the UI can't tell the difference.
 *
 * <p>This is what lets one 64-bit install dump 32-bit-only apps — upstream needed a separate
 * 32-bit build of the whole app for that.
 */
public final class Abi32Dumper {
    private static final String TAG = "BlackDex32Host";
    private static final String HELPER_MAIN = Abi32DumpMain.class.getName();
    private static final String[] HELPER_LIBS = {"libblackdex_d.so", "libblackdex.so"};
    private static final String HELPER_ABI = "armeabi-v7a";
    private static final long TIMEOUT_MS = 90_000;
    private static final long TIMEOUT_FIX_CODE_ITEM_MS = 10 * 60_000;

    private static final AtomicInteger sRunning = new AtomicInteger();
    private static Boolean sHelperAvailable;

    private Abi32Dumper() {
    }

    /** True while a 32-bit helper is dumping. */
    public static boolean isRunning() {
        return sRunning.get() > 0;
    }

    /** Device can run 32-bit code and this APK carries our 32-bit native libs. */
    public static synchronized boolean isAvailable() {
        if (sHelperAvailable == null) {
            boolean ok = false;
            if (BlackBoxCore.is64Bit() && AbiUtils.isDevice32BitCapable()) {
                try (ZipFile zip = new ZipFile(BlackBoxCore.getContext().getApplicationInfo().sourceDir)) {
                    ok = true;
                    for (String lib : HELPER_LIBS) {
                        if (zip.getEntry("lib/" + HELPER_ABI + "/" + lib) == null) ok = false;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "cannot inspect host apk", e);
                }
            }
            sHelperAvailable = ok;
        }
        return sHelperAvailable;
    }

    /** True if this (sandbox-installed) guest must be dumped by the 32-bit helper. */
    public static boolean shouldUse(String packageName) {
        try {
            ApplicationInfo ai = BlackBoxCore.getBPackageManager()
                    .getApplicationInfo(packageName, 0, BlackBoxCore.USER_ID);
            if (ai == null || !isAvailable()) return false;
            return AbiUtils.requires32BitRuntime(new File(ai.sourceDir)) || isForcedForTesting();
        } catch (Throwable e) {
            Log.w(TAG, "shouldUse(" + packageName + ")", e);
            return false;
        }
    }

    /**
     * Debug builds only: create {@code <external files>/force32} (e.g. via adb) to push every
     * dump through the 32-bit helper, so it can be tested with apps that also ship arm64 code.
     */
    private static boolean isForcedForTesting() {
        Context ctx = BlackBoxCore.getContext();
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false;
        File ext = ctx.getExternalFilesDir(null);
        return ext != null && new File(ext, "force32").exists();
    }

    /**
     * Start dumping an already sandbox-installed guest in the 32-bit helper. Returns immediately;
     * progress and the final result go to the registered dump monitors.
     */
    public static void dumpAsync(String packageName) {
        sRunning.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                dump(packageName);
            } finally {
                sRunning.decrementAndGet();
            }
        }, "abi32-dump-" + packageName);
        t.setDaemon(true);
        t.start();
    }

    private static void dump(String packageName) {
        Context ctx = BlackBoxCore.getContext();
        File outDir = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        result.dir = outDir.getAbsolutePath();
        File requestFile = new File(ctx.getCacheDir(), "abi32-" + packageName + ".req");
        java.lang.Process process = null;
        String terminal = null;
        try {
            FileUtils.deleteDir(outDir);
            FileUtils.mkdirs(outDir);

            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_META_DATA;
            PackageInfo pi = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, flags, BlackBoxCore.USER_ID);
            if (pi == null || pi.applicationInfo == null) {
                throw new IllegalStateException("package not installed in sandbox");
            }

            Abi32Request req = new Abi32Request();
            req.hostPackage = ctx.getPackageName();
            req.abi = HELPER_ABI;
            req.outDir = outDir.getAbsolutePath();
            req.fixCodeItem = BlackBoxCore.get().isFixCodeItem();
            req.hookDump = BlackBoxCore.get().isEnableHookDump();
            req.packageInfo = pi;
            req.write(requestFile);

            File libDir = ensureHelperLibs(ctx);
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/app_process32",
                    "-Djava.class.path=" + ctx.getApplicationInfo().sourceDir,
                    "-Djava.library.path=" + libDir.getAbsolutePath(),
                    "/system/bin",
                    HELPER_MAIN,
                    requestFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Log.i(TAG, "starting 32-bit helper for " + packageName);
            process = pb.start();

            final java.lang.Process proc = process;
            long timeout = req.fixCodeItem ? TIMEOUT_FIX_CODE_ITEM_MS : TIMEOUT_MS;
            Thread watchdog = new Thread(() -> {
                long deadline = System.currentTimeMillis() + timeout;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        proc.exitValue();
                        return; // exited
                    } catch (IllegalThreadStateException stillRunning) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                Log.w(TAG, "32-bit helper timed out, killing");
                proc.destroy();
            }, "abi32-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("PROGRESS ")) {
                        String[] parts = line.split(" ");
                        if (parts.length == 3) {
                            notice(result.dumpProcess(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                        }
                    } else if (line.startsWith("DONE") || line.startsWith("ERROR")) {
                        terminal = line;
                    } else {
                        Log.d(TAG, "helper: " + line);
                    }
                }
            }
            int exit = process.waitFor();
            watchdog.interrupt();
            Log.i(TAG, "32-bit helper exited " + exit + " terminal=" + terminal);

            File[] files = outDir.listFiles((dir, name) -> name.endsWith(".dex"));
            boolean haveDex = files != null && files.length > 0;
            if (haveDex) {
                // A helper killed by a packer after writing dex still counts as a successful dump.
                notice(result.dumpSuccess());
            } else if (terminal != null && terminal.startsWith("ERROR ")) {
                notice(result.dumpError(terminal.substring("ERROR ".length())));
            } else {
                notice(result.dumpError("32-bit helper exited (" + exit + ") without dumping any dex"));
            }
        } catch (Throwable e) {
            Log.e(TAG, "32-bit dump failed", e);
            if (process != null) process.destroy();
            notice(result.dumpError("32-bit dump failed: " + e.getMessage()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            requestFile.delete();
            try {
                BlackBoxCore.get().uninstallPackage(packageName);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notice(DumpResult result) {
        try {
            BlackBoxCore.getBDumpManager().noticeMonitor(result);
        } catch (Throwable e) {
            Log.w(TAG, "noticeMonitor failed", e);
        }
    }

    /**
     * Our APK is installed with its 64-bit libs extracted, so copy the 32-bit ones out of the APK
     * once per APK version. Loading .so files from app storage via dlopen is allowed; only exec()
     * of app files is blocked.
     */
    private static synchronized File ensureHelperLibs(Context ctx) throws Exception {
        ApplicationInfo ai = ctx.getApplicationInfo();
        File apk = new File(ai.sourceDir);
        File dir = new File(ctx.getCodeCacheDir(), "abi32");
        File stamp = new File(dir, ".stamp");
        String want = apk.lastModified() + ":" + apk.length();
        if (stamp.exists() && want.equals(readSmall(stamp))) {
            boolean all = true;
            for (String lib : HELPER_LIBS) all &= new File(dir, lib).isFile();
            if (all) return dir;
        }
        FileUtils.deleteDir(dir);
        FileUtils.mkdirs(dir);
        try (ZipFile zip = new ZipFile(apk)) {
            for (String lib : HELPER_LIBS) {
                ZipEntry e = zip.getEntry("lib/" + HELPER_ABI + "/" + lib);
                if (e == null) throw new IllegalStateException("APK has no 32-bit " + lib);
                File out = new File(dir, lib);
                try (InputStream in = zip.getInputStream(e); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                out.setReadOnly();
            }
        }
        try (OutputStream os = new FileOutputStream(stamp)) {
            os.write(want.getBytes());
        }
        return dir;
    }

    private static String readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n);
        } catch (Throwable e) {
            return "";
        }
    }
}
