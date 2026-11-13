#ifndef OCTO_ALLOC_COUNT_H
#define OCTO_ALLOC_COUNT_H

// Counts the calls into the C allocator made on the calling thread between arm and disarm, for
// tests that prove code allocates nothing (the render thread's blend session). Only on Linux with
// glibc, where the test binary's own malloc family takes the place of the C library's and hands
// each call on; elsewhere nothing is counted and supported answers 0.

int octo_alloc_supported(void);
void octo_alloc_arm(void);
long octo_alloc_disarm(void);

#endif
