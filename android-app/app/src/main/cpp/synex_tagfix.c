/*
 * Synex Host - Android ARM64 child-JVM heap compatibility shim.
 *
 * An APK manifest opt-out can affect the Zygote-created UI process but
 * standalone OpenJDK starts via execve(). To address Bionic's pointer-tag
 * free() abort in that child, preload this library before libjvm initialization.
 *
 * Based on the API selection in Termux OpenJDK's Android 12 workaround:
 * Android API 31+: mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0).
 * Android API 30: android_mallopt(M_SET_HEAP_TAGGING_LEVEL, &zero, sizeof(zero)).
 *
 * Temporary compatibility approach; it lowers heap tagging protections in
 * the server JVM process and is not a substitute for fixing OpenJDK itself.
 */
#include <dlfcn.h>
#include <malloc.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif

__attribute__((constructor))
static void synex_disable_incompatible_heap_pointer_tags(void) {
    bool disabled = false;
#if defined(__aarch64__)
    /* API 31+ uses Bionic's public mallopt() option. */
    if (mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0) != 0) {
        disabled = true;
    }
    if (!disabled) {
        /* Android 11 uses the prior Bionic interface. */
        typedef bool (*legacy_mallopt_t)(int, void*, size_t);
        legacy_mallopt_t legacy = (legacy_mallopt_t)dlsym(RTLD_DEFAULT, "android_mallopt");
        if (legacy != NULL) {
            uint32_t no_tags = 0;
            disabled = legacy(8, &no_tags, sizeof(no_tags));
        }
    }
#endif
    const char* result = disabled
        ? "[Synex/native] SYNEX_HEAP_TAGGING_DISABLED - Bionic JVM compat active\n"
        : "[Synex/native] HEAP_TAGGING_DISABLE_FAILED - Bionic API unavailable\n";
    (void)write(STDERR_FILENO, result, strlen(result));
}
