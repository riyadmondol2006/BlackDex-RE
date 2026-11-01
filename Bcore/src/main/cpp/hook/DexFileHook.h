//
// Records every dex the guest opens through the Java DexFile APIs.
//

#ifndef BLACKDEX_DEXFILEHOOK_H
#define BLACKDEX_DEXFILEHOOK_H
#include "BaseHook.h"

class DexFileHook : public BaseHook {
public:
    static void init(JNIEnv *env);
};

#endif //BLACKDEX_DEXFILEHOOK_H
