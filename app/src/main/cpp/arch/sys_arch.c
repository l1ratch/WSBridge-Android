#include "arch/sys_arch.h"
#include <time.h>

// sys_now: monotonic milliseconds (lwIP timers). CLOCK_MONOTONIC on Android ==
// mach_absolute_time semantics on iOS — never jumps with wall clock.
u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t)(ts.tv_sec * 1000UL + ts.tv_nsec / 1000000UL);
}

sys_prot_t sys_arch_protect(void) {
    // NO_SYS=1: single-threaded, no protection needed
    return 0;
}

void sys_arch_unprotect(sys_prot_t pval) {
    (void)pval;
}
