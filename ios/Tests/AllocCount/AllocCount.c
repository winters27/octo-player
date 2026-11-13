#include "AllocCount.h"

// glibc says who it is in features.h, which nothing has included yet.
#if defined(__linux__)
#include <features.h>
#endif

#if defined(__linux__) && defined(__GLIBC__)

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <unistd.h>

// The C library's own allocator, which every call here hands on to. glibc exports no
// __libc_posix_memalign, __libc_aligned_alloc or public __libc_reallocarray, so those go through
// __libc_memalign and __libc_realloc with their own contracts kept.
extern void *__libc_malloc(size_t size);
extern void *__libc_calloc(size_t count, size_t size);
extern void *__libc_realloc(void *pointer, size_t size);
extern void __libc_free(void *pointer);
extern void *__libc_memalign(size_t alignment, size_t size);
extern void *__libc_valloc(size_t size);

// Armed and counted per thread, so tests running in parallel never count each other's calls.
static __thread int armed;
static __thread long calls;

static inline void count(void) {
    if (armed) calls++;
}

int octo_alloc_supported(void) { return 1; }

void octo_alloc_arm(void) {
    calls = 0;
    armed = 1;
}

long octo_alloc_disarm(void) {
    armed = 0;
    return calls;
}

void *malloc(size_t size) {
    count();
    return __libc_malloc(size);
}

void *calloc(size_t count_, size_t size) {
    count();
    return __libc_calloc(count_, size);
}

void *realloc(void *pointer, size_t size) {
    count();
    return __libc_realloc(pointer, size);
}

void *reallocarray(void *pointer, size_t count_, size_t size) {
    count();
    size_t total;
    if (__builtin_mul_overflow(count_, size, &total)) {
        errno = ENOMEM;
        return NULL;
    }
    return __libc_realloc(pointer, total);
}

void free(void *pointer) {
    count();
    __libc_free(pointer);
}

void *memalign(size_t alignment, size_t size) {
    count();
    return __libc_memalign(alignment, size);
}

void *valloc(size_t size) {
    count();
    return __libc_valloc(size);
}

void *pvalloc(size_t size) {
    count();
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    size_t rounded = size == 0 ? page : (size + page - 1) / page * page;
    return __libc_memalign(page, rounded);
}

int posix_memalign(void **out, size_t alignment, size_t size) {
    count();
    if (alignment < sizeof(void *) || (alignment & (alignment - 1)) != 0) return EINVAL;
    void *pointer = __libc_memalign(alignment, size);
    if (pointer == NULL && size != 0) return ENOMEM;
    *out = pointer;
    return 0;
}

void *aligned_alloc(size_t alignment, size_t size) {
    count();
    return __libc_memalign(alignment, size);
}

#else

int octo_alloc_supported(void) { return 0; }
void octo_alloc_arm(void) {}
long octo_alloc_disarm(void) { return 0; }

#endif
