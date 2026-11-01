//
// Packers frequently decrypt the real dex and load it into a private child classloader
// (DexClassLoader / InMemoryDexClassLoader) that nothing global references, so walking the
// app's classloader chain never finds it. Every Java-API dex load ends in one of the native
// DexFile entry points below; hook them and hand each returned cookie to
// VMCore.onDexOpened(Object, String), which the cookie dumper then includes.
//

#include "DexFileHook.h"
#include <JniHook.h>
#include "VmCore.h"
#include "utils/Log.h"

static jclass gVMCoreClass = nullptr;
static jmethodID gOnDexOpened = nullptr;

static jobject report(JNIEnv *env, jobject cookie, jstring source = nullptr) {
    if (cookie == nullptr || gOnDexOpened == nullptr || env->ExceptionCheck()) {
        return cookie;
    }
    env->CallStaticVoidMethod(gVMCoreClass, gOnDexOpened, cookie, source);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return cookie;
}

// API 26+ (and N): (String, String, int, ClassLoader, DexPathList$Element[])Object
HOOK_JNI(jobject, openDexFileNative5, JNIEnv *env, jclass clazz, jstring src, jstring out,
         jint flags, jobject loader, jobjectArray elements) {
    return report(env, orig_openDexFileNative5(env, clazz, src, out, flags, loader, elements), src);
}

// API 21-23: (String, String, int)Object
HOOK_JNI(jobject, openDexFileNative3, JNIEnv *env, jclass clazz, jstring src, jstring out,
         jint flags) {
    return report(env, orig_openDexFileNative3(env, clazz, src, out, flags), src);
}

// API 29+: InMemoryDexClassLoader
HOOK_JNI(jobject, openInMemoryDexFilesNative, JNIEnv *env, jclass clazz, jobjectArray buffers,
         jobjectArray arrays, jintArray starts, jintArray ends, jobject loader,
         jobjectArray elements) {
    return report(env, orig_openInMemoryDexFilesNative(env, clazz, buffers, arrays, starts, ends,
                                                       loader, elements));
}

// API 26-28: InMemoryDexClassLoader
HOOK_JNI(jobject, createCookieWithDirectBuffer, JNIEnv *env, jclass clazz, jobject buffer,
         jint start, jint end, jobject loader, jobjectArray elements) {
    return report(env, orig_createCookieWithDirectBuffer(env, clazz, buffer, start, end, loader,
                                                         elements));
}

HOOK_JNI(jobject, createCookieWithArray, JNIEnv *env, jclass clazz, jbyteArray buffer,
         jint start, jint end, jobject loader, jobjectArray elements) {
    return report(env, orig_createCookieWithArray(env, clazz, buffer, start, end, loader,
                                                  elements));
}

void DexFileHook::init(JNIEnv *env) {
    jclass vmCore = env->FindClass(VMCORE_CLASS);
    if (vmCore == nullptr) {
        env->ExceptionClear();
        return;
    }
    gVMCoreClass = (jclass) env->NewGlobalRef(vmCore);
    gOnDexOpened = env->GetStaticMethodID(gVMCoreClass, "onDexOpened", "(Ljava/lang/Object;Ljava/lang/String;)V");
    if (gOnDexOpened == nullptr) {
        env->ExceptionClear();
        ALOGE("DexFileHook: VMCore.onDexOpened missing");
        return;
    }

    const char *dexFile = "dalvik/system/DexFile";
    // HookJniFun silently skips signatures that don't exist on this API level.
    JniHook::HookJniFun(env, dexFile, "openDexFileNative",
                        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                        (void *) new_openDexFileNative5, (void **) &orig_openDexFileNative5, true);
    JniHook::HookJniFun(env, dexFile, "openDexFileNative",
                        "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                        (void *) new_openDexFileNative3, (void **) &orig_openDexFileNative3, true);
    JniHook::HookJniFun(env, dexFile, "openInMemoryDexFilesNative",
                        "([Ljava/nio/ByteBuffer;[[B[I[ILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                        (void *) new_openInMemoryDexFilesNative,
                        (void **) &orig_openInMemoryDexFilesNative, true);
    JniHook::HookJniFun(env, dexFile, "createCookieWithDirectBuffer",
                        "(Ljava/nio/ByteBuffer;IILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                        (void *) new_createCookieWithDirectBuffer,
                        (void **) &orig_createCookieWithDirectBuffer, true);
    JniHook::HookJniFun(env, dexFile, "createCookieWithArray",
                        "([BIILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                        (void *) new_createCookieWithArray, (void **) &orig_createCookieWithArray,
                        true);
    ALOGD("DexFileHook installed");
}
