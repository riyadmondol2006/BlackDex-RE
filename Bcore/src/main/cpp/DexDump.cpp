//
// Created by Milk on 2021/5/16.
//

#include "DexDump.h"
#include "utils/HexDump.h"
#include "utils/Log.h"
#include "VmCore.h"
#include "utils/PointerCheck.h"
#include "jnihook/Art.h"
#include "jnihook/ArtM.h"

#include <cstdio>
#include <fcntl.h>
#include <cstring>
#include <cstdlib>
#include <map>
#include <list>
#include <sys/mman.h>
#include "unistd.h"
#include <signal.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>

#include "dex/dex_file-inl.h"
#include "dex/dex_file_loader.h"
#include "jnihook/JniHook.h"
#include "xhook/xhook.h"
#include "Dobby/include/dobby.h"

#define _uintval(p)               reinterpret_cast<uintptr_t>(p)
#define _ptr(p)                   reinterpret_cast<void *>(p)
#define _align_up(x, n)           (((x) + ((n) - 1)) & ~((n) - 1))
#define _align_down(x, n)         ((x) & -(n))
// Android 15+ supports 16 KB memory pages; never assume 4096 (mprotect would EINVAL).
static inline size_t _runtime_page_size() {
    static size_t ps = 0;
    if (ps == 0) {
        long v = sysconf(_SC_PAGESIZE);
        ps = (v > 0) ? (size_t) v : 4096;
    }
    return ps;
}
#define _page_size                (_runtime_page_size())
#define _page_align(n)            _align_up(static_cast<uintptr_t>(n), _page_size)
#define _ptr_align(x)             _ptr(_align_down(reinterpret_cast<uintptr_t>(x), _page_size))
#define _make_rwx(p, n)           ::mprotect(_ptr_align(p), \
                                              _page_align(_uintval(p) + n) != _page_align(_uintval(p)) ? _page_align(n) + _page_size : _page_align(n), \
                                              PROT_READ | PROT_WRITE | PROT_EXEC)

// A valid standard/compact dex image starts with "dex\n0xx\0" or "cdex001\0".
static inline bool is_dex_magic(const unsigned char *p) {
    if (p == nullptr) return false;
    if (p[0] == 'd' && p[1] == 'e' && p[2] == 'x' && p[3] == '\n' &&
        p[4] == '0' && p[7] == '\0') {
        return true; // dex\n035 / 037 / 038 / 039 / 040 / 041
    }
    if (p[0] == 'c' && p[1] == 'd' && p[2] == 'e' && p[3] == 'x') {
        return true; // CompactDex
    }
    return false;
}

using namespace std;
static const char *dumpPath;
std::list<int> dumped;

void handleDumpByDexFile(void *dex_file) {
    char magic[8] = {0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00};
    if (!PointerCheck::check(dex_file)) {
        return;
    }
    auto dexFile = static_cast<art_lkchan::DexFile *>(dex_file);

    int size = dexFile->Size();
    list<int>::iterator iterator;
    for (iterator = dumped.begin(); iterator != dumped.end(); ++iterator) {
        int value = *iterator;
        if (size == value) {
            return;
        }
    }
    void *buffer = malloc(size);
    if (buffer) {
        memcpy(buffer, dexFile->Begin(), size);
        // fix magic
        memcpy(buffer, magic, sizeof(magic));

        const bool kVerifyChecksum = false;
        const bool kVerify = true;
        const art_lkchan::DexFileLoader dex_file_loader;
        std::string error_msg;
        std::vector<std::unique_ptr<const art_lkchan::DexFile>> dex_files;
        if (!dex_file_loader.OpenAll(reinterpret_cast<const uint8_t *>(buffer),
                                     size,
                                     "",
                                     kVerify,
                                     kVerifyChecksum,
                                     &error_msg,
                                     &dex_files)) {
            // Display returned error message to user. Note that this error behavior
            // differs from the error messages shown by the original Dalvik dexdump.
            ALOGE("Open dex error %s", error_msg.c_str());
            return;
        }

        char path[1024];
        sprintf(path, "%s/hook_%d.dex", dumpPath, size);
        auto fd = open(path, O_CREAT | O_WRONLY, 0600);
        ssize_t w = write(fd, buffer, size);
        fsync(fd);
        if (w > 0) {
            ALOGE("hook dump dex ======> %s", path);
        } else {
            remove(path);
        }
        close(fd);
        free(buffer);
        dumped.push_back(size);
    }
}

// A fatal signal would tear down the process; packers use these to self-destruct once they
// detect analysis. Swallow fatal signals, pass everything else through to the real libc.
static inline bool is_fatal_signal(int sig) {
    return sig == SIGKILL || sig == SIGABRT || sig == SIGSEGV ||
           sig == SIGBUS || sig == SIGILL || sig == 9 || sig == 6;
}

HOOK_JNI(int, kill, pid_t __pid, int __signal) {
    if (is_fatal_signal(__signal)) {
        ALOGE("anti-kill: swallowed kill(%d, %d)", __pid, __signal);
        return 0;
    }
    return orig_kill(__pid, __signal);
}

HOOK_JNI(int, killpg, int __pgrp, int __signal) {
    if (is_fatal_signal(__signal)) {
        ALOGE("anti-kill: swallowed killpg(%d, %d)", __pgrp, __signal);
        return 0;
    }
    return orig_killpg(__pgrp, __signal);
}

HOOK_JNI(int, tgkill, int __tgid, int __tid, int __signal) {
    if (is_fatal_signal(__signal)) {
        ALOGE("anti-kill: swallowed tgkill(%d, %d, %d)", __tgid, __tid, __signal);
        return 0;
    }
    return orig_tgkill(__tgid, __tid, __signal);
}

HOOK_JNI(int, raise, int __signal) {
    if (is_fatal_signal(__signal)) {
        ALOGE("anti-kill: swallowed raise(%d)", __signal);
        return 0;
    }
    return orig_raise(__signal);
}

HOOK_JNI(int, pthread_kill, pthread_t thread_id, int __signal) {
    if (is_fatal_signal(__signal)) {
        ALOGE("anti-kill: swallowed pthread_kill(%d)", __signal);
        return 0;
    }
    return orig_pthread_kill(thread_id, __signal);
}

HOOK_JNI(void, abort, void) {
    ALOGE("anti-kill: swallowed abort()");
    // Do not call the real abort(); return to caller instead.
}

// Packers (Ijiami) fork a watchdog child that ptrace-attaches the main process with
// PTRACE_O_EXITKILL and then exits, making the kernel SIGKILL the main process (a kill with
// no userspace sender — unblockable by signal hooks). Neutralise the lethal trace: pretend
// TRACEME/ATTACH/SEIZE succeed without establishing a real trace, and strip EXITKILL from
// SETOPTIONS. The guest is never actually debugged, so faking success is safe.
HOOK_JNI(long, ptrace, int __request, pid_t __pid, void *__addr, void *__data) {
    const int PT_TRACEME = 0;
    const int PT_ATTACH = 16;
    const int PT_SEIZE = 0x4206;
    const int PT_SETOPTIONS = 0x4200;
    const long PT_O_EXITKILL = 0x00100000;
    if (__request == PT_TRACEME || __request == PT_ATTACH || __request == PT_SEIZE) {
        ALOGE("anti-kill: faked ptrace(request=0x%x)", __request);
        return 0;
    }
    if (__request == PT_SETOPTIONS) {
        __data = (void *) (((long) __data) & ~PT_O_EXITKILL);
    }
    return orig_ptrace(__request, __pid, __addr, __data);
}

HOOK_FUN(void, LoadMethodO, void *thiz,
         void *dex_file,
         void *method,
         void *klass,
         void *dst) {
    orig_LoadMethodO(thiz, dex_file, method, klass, dst);
    handleDumpByDexFile(dex_file);
}

HOOK_FUN(void, LoadMethodM, void *thiz,
         void *thread,
         void *dex_file,
         void *method,
         void *klass,
         void *dst) {
    orig_LoadMethodM(thiz, thread, dex_file, method, klass, dst);
    handleDumpByDexFile(dex_file);
}

HOOK_FUN(void, LoadMethodL, void *thiz,
         void *thread,
         void *dex_file,
         void *method,
         void *klass) {
    orig_LoadMethodL(thiz, thread, dex_file, method, klass);
    handleDumpByDexFile(dex_file);
}

// Install the anti-self-kill xhooks once. Packers (e.g. Ijiami) call kill()/killpg() to
// terminate the process once they detect analysis; swallowing those keeps the app alive
// long enough to dump. (The begin_ offset heuristic that used to live here is gone — the
// dex image is now located by magic scan, independent of ART struct layout.)
// Raw tgkill/kill/exit_group issued through syscall() bypass any PLT/GOT hook, so intercept
// libc's syscall() wrapper too and swallow fatal process-/thread-kills and exit_group.
long (*orig_syscall)(long, ...) = nullptr;

long new_syscall(long number, long a1, long a2, long a3, long a4, long a5, long a6) {
#if defined(__aarch64__)
    const long NR_kill = 129, NR_tgkill = 131, NR_tkill = 130, NR_exit_group = 94;
#elif defined(__arm__)
    const long NR_kill = 37, NR_tgkill = 268, NR_tkill = 238, NR_exit_group = 248;
#else
    const long NR_kill = -1, NR_tgkill = -1, NR_tkill = -1, NR_exit_group = -1;
#endif
    if (number == NR_exit_group) {
        ALOGE("anti-kill: swallowed syscall(exit_group)");
        return 0;
    }
    if (number == NR_tgkill && is_fatal_signal((int) a3)) {
        ALOGE("anti-kill: swallowed syscall(tgkill,%ld,%ld,%ld)", a1, a2, a3);
        return 0;
    }
    if ((number == NR_kill || number == NR_tkill) && is_fatal_signal((int) a2)) {
        ALOGE("anti-kill: swallowed syscall(kill/tkill,%ld,%ld)", a1, a2);
        return 0;
    }
    return orig_syscall(number, a1, a2, a3, a4, a5, a6);
}

// Block self-kill issued through RAW syscalls (inline svc), which no PLT/inline libc hook can
// see. A seccomp BPF filter inspects the signal argument of kill/tkill/tgkill at the kernel
// boundary and makes a fatal one fail with EPERM instead of executing — the process survives
// its own packer's self-destruct. Uses SECCOMP_RET_ERRNO only (no SIGSYS handler, so no added
// crash surface). Seccomp filters stack, so this layers on top of Android's app filter.
static void install_seccomp_anti_kill() {
#if defined(__aarch64__)
    const uint32_t NATIVE_ARCH = AUDIT_ARCH_AARCH64;
    const uint32_t NR_kill = 129, NR_tkill = 130, NR_tgkill = 131;
#elif defined(__arm__)
    const uint32_t NATIVE_ARCH = AUDIT_ARCH_ARM;
    const uint32_t NR_kill = 37, NR_tkill = 238, NR_tgkill = 268;
#else
    const uint32_t NATIVE_ARCH = 0xffffffff;
    const uint32_t NR_kill = 0xffff0, NR_tkill = 0xffff1, NR_tgkill = 0xffff2;
#endif
    // 16-instruction filter; jump offsets verified by index (see layout comment above each).
    struct sock_filter filter[] = {
            /* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
            /* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NATIVE_ARCH, 1, 0),
            /* 2 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),              // wrong arch
            /* 3 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            /* 4 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_tgkill, 3, 0),      // -> 8
            /* 5 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_kill, 5, 0),        // -> 11
            /* 6 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_tkill, 4, 0),       // -> 11
            /* 7 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),             // not a kill syscall
            /* 8 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
            /* 9 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 9, 5, 0),              // sig==SIGKILL -> 15
            /*10 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 6, 4, 3),              // sig==SIGABRT ->15 else ->14
            /*11 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
            /*12 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 9, 2, 0),              // -> 15
            /*13 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 6, 1, 0),              // -> 15 else ->14
            /*14 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),             // not fatal
            // Return SUCCESS (0) rather than an error: the packer believes its self-kill
            // worked and proceeds gracefully instead of spinning/retrying (which would hang
            // the process and trip ART's thread-suspend-timeout abort).
            /*15 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (0 & SECCOMP_RET_DATA)),
    };
    struct sock_fprog prog = {
            .len = (unsigned short) (sizeof(filter) / sizeof(filter[0])),
            .filter = filter,
    };
    // NO_NEW_PRIVS is already set for app processes, but set it again defensively.
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
        ALOGE("anti-kill: seccomp install failed: %s", strerror(errno));
    } else {
        ALOGE("anti-kill: seccomp filter installed");
    }
}

static bool sKillHooksInstalled = false;

static void dobby_hook_libc(const char *sym, void *replace, void **orig) {
    void *addr = DobbySymbolResolver("libc.so", sym);
    if (addr != nullptr) {
        DobbyHook(addr, replace, orig);
    }
}

void init(JNIEnv *env) {
    if (sKillHooksInstalled) {
        return;
    }
    sKillHooksInstalled = true;
    // Kernel-level guard first: catches RAW-syscall self-kills (inline svc) that no userspace
    // function hook can see. This is the one that matters for hardened packers like Ijiami.
    install_seccomp_anti_kill();
    // Inline-hook libc directly (via Dobby) so callers are caught regardless of when their
    // library was loaded — the packer's libexecmain.so is dlopen'd AFTER this runs, which is
    // exactly why the old PLT/GOT (xhook) approach missed it.
    dobby_hook_libc("kill", (void *) new_kill, (void **) &orig_kill);
    dobby_hook_libc("killpg", (void *) new_killpg, (void **) &orig_killpg);
    dobby_hook_libc("tgkill", (void *) new_tgkill, (void **) &orig_tgkill);
    dobby_hook_libc("raise", (void *) new_raise, (void **) &orig_raise);
    dobby_hook_libc("pthread_kill", (void *) new_pthread_kill, (void **) &orig_pthread_kill);
    dobby_hook_libc("ptrace", (void *) new_ptrace, (void **) &orig_ptrace);
    // NOTE: libc syscall() is intentionally NOT inline-hooked — every syscall would route
    // through our wrapper and the variadic ABI is easy to get subtly wrong, which destabilises
    // the whole process. The dedicated wrappers above cover the realistic self-kill vectors.
}

// Public entry so the anti-self-kill hooks can be installed EARLY (before the target app's
// packer runs during makeApplication), not lazily at dump time.
void DexDump::installAntiKill(JNIEnv *env) {
    init(env);
}

void fixCodeItem(JNIEnv *env, const art_lkchan::DexFile *dex_file_, size_t begin) {
    for (size_t classdef_ctr = 0; classdef_ctr < dex_file_->NumClassDefs(); ++classdef_ctr) {
        const art_lkchan::DexFile::ClassDef &cd = dex_file_->GetClassDef(classdef_ctr);
        const uint8_t *class_data = dex_file_->GetClassData(cd);
        auto &classTypeId = dex_file_->GetTypeId(cd.class_idx_);
        std::string class_name = dex_file_->GetTypeDescriptor(classTypeId);

        if (class_data != nullptr) {
            art_lkchan::ClassDataItemIterator cdit(*dex_file_, class_data);
            cdit.SkipAllFields();
            while (cdit.HasNextMethod()) {
                const art_lkchan::DexFile::MethodId &method_id_item = dex_file_->GetMethodId(
                        cdit.GetMemberIndex());
                auto method_name = dex_file_->GetMethodName(method_id_item);
                auto method_signature = dex_file_->GetMethodSignature(
                        method_id_item).ToString().c_str();
                auto java_method = VmCore::findMethod(env, class_name.c_str(), method_name,
                                                      method_signature);
                if (java_method) {
                    auto artMethod = ArtM::GetArtMethod(env, java_method);
                    const art_lkchan::DexFile::CodeItem *orig_code_item = cdit.GetMethodCodeItem();
                    if (cdit.GetMethodCodeItemOffset() && orig_code_item) {
                        auto codeItemSize = dex_file_->GetCodeItemSize(*orig_code_item);
                        auto new_code_item =
                                begin + ArtM::GetArtMethodDexCodeItemOffset(artMethod);
                        memcpy((void *) orig_code_item,
                               (void *) new_code_item,
                               codeItemSize);
                    }
                } else {
                    env->ExceptionClear();
                }
                cdit.Next();
            }
        }
    }
}

// Locate the native dex image (begin_) inside an ART DexFile object in a way that does NOT
// depend on the exact C++ field layout (which changed across Android 13/14/15/16/17). The
// DexFile object is scanned for the first member slot that is a pointer to memory beginning
// with a dex/cdex magic; that is begin_. The dex size is then read from the on-disk dex
// header (file_size_ @ +0x20), whose layout is frozen — immune to ART's struct churn.
static const unsigned char *find_dex_begin(const char *dexFileObj) {
    if (!PointerCheck::check((void *) dexFileObj)) {
        return nullptr;
    }
    for (int i = 0; i < 24; ++i) {
        auto slot = *reinterpret_cast<unsigned char *const *>(dexFileObj + i * sizeof(void *));
        if (PointerCheck::check((void *) slot) && is_dex_magic(slot)) {
            return slot;
        }
    }
    return nullptr;
}

void DexDump::cookieDumpDex(JNIEnv *env, jlong cookie, jstring dir, jboolean fix) {
    init(env); // ensure anti-self-kill hooks are installed
    char magic[8] = {0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00};
    auto base = reinterpret_cast<char *>(cookie);

    const unsigned char *begin = find_dex_begin(base);
    if (begin == nullptr) {
        ALOGD("dumpDex: could not locate dex begin for cookie %p", (void *) base);
        return;
    }

    // file_size_ lives at offset 0x20 of the dex header (uint32, little-endian).
    uint32_t size = *reinterpret_cast<const uint32_t *>(begin + 0x20);
    if (size < 0x70 || size > (64u * 1024u * 1024u)) {
        ALOGD("dumpDex: implausible dex size %u, skipping", size);
        return;
    }

    auto dirC = env->GetStringUTFChars(dir, 0);
    void *buffer = malloc(size);
    if (buffer) {
        memcpy(buffer, begin, size);
        // Restore a standard magic so downstream tooling accepts the image.
        memcpy(buffer, magic, sizeof(magic));

        // Best-effort verification only drives the fixCodeItem pass; a dumped dex that fails
        // re-verification is still written out (it is often usable / repairable).
        const bool kVerifyChecksum = false;
        const bool kVerify = false;
        const art_lkchan::DexFileLoader dex_file_loader;
        std::string error_msg;
        std::vector<std::unique_ptr<const art_lkchan::DexFile>> dex_files;
        bool opened = dex_file_loader.OpenAll(reinterpret_cast<const uint8_t *>(buffer),
                                              size, "", kVerify, kVerifyChecksum,
                                              &error_msg, &dex_files);
        if (fix && opened && !dex_files.empty()) {
            fixCodeItem(env, dex_files[0].get(), (size_t) begin);
        }

        char path[1024];
        snprintf(path, sizeof(path), "%s/cookie_%u.dex", dirC, size);
        auto fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
        if (fd >= 0) {
            ssize_t w = write(fd, buffer, size);
            fsync(fd);
            if (w > 0) {
                ALOGE("cookie dump dex ======> %s", path);
            } else {
                remove(path);
            }
            close(fd);
        }
        free(buffer);
    }
    env->ReleaseStringUTFChars(dir, dirC);
}

void DexDump::hookDumpDex(JNIEnv *env, jstring dir) {
    dumpPath = env->GetStringUTFChars(dir, 0);
    const char *libart = "libart.so";

    // L
    void *loadMethod = DobbySymbolResolver(libart,
                                           "_ZN3art11ClassLinker10LoadMethodEPNS_6ThreadERKNS_7DexFileERKNS_21ClassDataItemIteratorENS_6HandleINS_6mirror5ClassEEE");
    if (!loadMethod) {
        // M
        loadMethod = DobbySymbolResolver(libart,
                                         "_ZN3art11ClassLinker10LoadMethodEPNS_6ThreadERKNS_7DexFileERKNS_21ClassDataItemIteratorENS_6HandleINS_6mirror5ClassEEEPNS_9ArtMethodE");
    }
    if (!loadMethod) {
        // O
        loadMethod = DobbySymbolResolver(libart,
                                         "_ZN3art11ClassLinker10LoadMethodERKNS_7DexFileERKNS_13ClassAccessor6MethodENS_6HandleINS_6mirror5ClassEEEPNS_9ArtMethodE");
    }

    _make_rwx(loadMethod, _page_size);
    if (loadMethod) {
        if (android_get_device_api_level() >= __ANDROID_API_O__) {
            DobbyHook(loadMethod, (void *) new_LoadMethodO,
                      (void **) &orig_LoadMethodO);
        } else if (android_get_device_api_level() >= __ANDROID_API_M__) {
            DobbyHook(loadMethod, (void *) new_LoadMethodM,
                      (void **) &orig_LoadMethodM);
        } else {
            DobbyHook(loadMethod, (void *) new_LoadMethodL,
                      (void **) &orig_LoadMethodL);
        }
    }
}

