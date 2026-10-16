package top.niunaijun.blackbox.utils.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Created by Milk on 2021/5/16.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class DexFileCompat {
    public static final String TAG = "DexFileCompat";

    public static List<String> getClassNameList(ClassLoader classLoader) {
        List<String> allClass = new ArrayList<>();
        try {
            List<DexFile> dexFiles = getDexFiles(classLoader);
            for (DexFile dexFile : dexFiles) {
                Object object = Reflector.with(dexFile)
                        .field("mCookie")
                        .get();
                String[] classNameList = getClassNameList(object);
                allClass.addAll(Arrays.asList(classNameList));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return allClass;
    }

    private static String[] getClassNameList(Object cookie) {
        try {
            String[] list;
            if (BuildCompat.isM()) {
                list = Reflector.on(DexFile.class)
                        .method("getClassNameList", Object.class)
                        .call(cookie);
            } else {
                list = Reflector.on(DexFile.class)
                        .method("getClassNameList", long.class)
                        .call(cookie);
            }
            return list;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static List<Long> getCookies(ClassLoader classLoader) {
        List<Long> cookies = new ArrayList<>();
        List<DexFile> dexFiles = getDexFiles(classLoader);
        for (DexFile dexFile : dexFiles) {
            cookies.addAll(getCookies(dexFile));
        }
        return cookies;
    }

    /**
     * Collect cookies from the given classloader AND its full parent chain, de-duplicated.
     * Packers frequently load the real dex into a child/replacement DexClassLoader, so walking
     * only one classloader misses the decrypted code.
     */
    public static List<Long> getAllCookies(ClassLoader classLoader) {
        List<Long> cookies = new ArrayList<>();
        ClassLoader cl = classLoader;
        int guard = 0;
        while (cl != null && guard++ < 32) {
            try {
                for (long c : getCookies(cl)) {
                    if (c != 0 && !cookies.contains(c)) {
                        cookies.add(c);
                    }
                }
            } catch (Throwable ignored) {
            }
            cl = cl.getParent();
        }
        return cookies;
    }

    /** All class names across the classloader chain's dex files. */
    public static List<String> getAllClassNames(ClassLoader classLoader) {
        List<String> all = new ArrayList<>();
        ClassLoader cl = classLoader;
        int guard = 0;
        while (cl != null && guard++ < 32) {
            try {
                for (String n : getClassNameList(cl)) {
                    if (n != null && !all.contains(n)) {
                        all.add(n);
                    }
                }
            } catch (Throwable ignored) {
            }
            cl = cl.getParent();
        }
        return all;
    }

    public static List<Long> getCookies(DexFile dexFile) {
        List<Long> cookies = new ArrayList<>();
        if (dexFile == null)
            return cookies;
        try {
            Object object = Reflector.with(dexFile)
                    .field("mCookie")
                    .get();
            if (BuildCompat.isM()) {
                for (long l : (long[]) object) {
                    cookies.add(l);
                }
            } else {
                cookies.add((long) object);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cookies;
    }

    private static List<DexFile> getDexFiles(ClassLoader classLoader) {
        List<DexFile> dexFiles = new ArrayList<>();
        Object[] dexElements = getDexElements(classLoader);
        for (Object dexElement : dexElements) {
            try {
                DexFile dexFile = Reflector.with(dexElement).field("dexFile").get();
                if (dexFile != null && !isStubDex(dexFile)) {
                    dexFiles.add(dexFile);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return dexFiles;
    }

    private static String dexFileName(DexFile dexFile) {
        try {
            String n = Reflector.with(dexFile).field("mFileName").get();
            return n == null ? "null" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Skip dexes that belong to BlackDex itself, not the target app: the injected vm/junit/empty
     * stub apks, and the host app's own apk (which appears in the parent classloader chain). We
     * walk the whole chain so a packer's parent-side dex is still captured, but we don't want to
     * dump our own framework alongside it.
     */
    private static boolean isStubDex(DexFile dexFile) {
        try {
            String name = Reflector.with(dexFile).field("mFileName").get();
            if (name == null) return false;
            if (name.contains("/vm.apk") || name.contains("/junit.apk")
                    || name.contains("/empty.apk") || name.contains("/empty.jar")
                    || name.contains("/junit.jar")) {
                return true;
            }
            // The host app's own apk (e.g. /data/app/~~.../com.riyadm.dexre-xxx/base.apk) and its
            // split/boot framework jars.
            String hostPkg = top.niunaijun.blackbox.BlackBoxCore.getHostPkg();
            if (hostPkg != null && name.contains("/" + hostPkg + "-")) {
                return true;
            }
            if (name.startsWith("/system/") || name.startsWith("/apex/")) {
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object[] getDexElements(ClassLoader classLoader) {
        Object dexPathList = getDexPathList(classLoader);
        if (dexPathList == null) {
            return new Object[]{};
        }
        try {
            return Reflector.with(dexPathList)
                    .field("dexElements")
                    .get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Object[]{};
    }

    private static Object getDexPathList(ClassLoader classLoader) {
        // BootClassLoader / non-BaseDexClassLoader have no pathList — skip quietly (the parent
        // chain ends at BootClassLoader, which is expected, not an error).
        if (!(classLoader instanceof dalvik.system.BaseDexClassLoader)) {
            return null;
        }
        try {
            return Reflector.on("dalvik.system.BaseDexClassLoader")
                    .field("pathList")
                    .get(classLoader);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
