// The C ABI of desktop/bridge (libnekokem_native): one UTF-8 JSON request and
// one JSON reply per call. See desktop/bridge/src/lib.rs.
#ifndef NEKOKEM_DESKTOP_H
#define NEKOKEM_DESKTOP_H
#include <stddef.h>
#include <stdint.h>

typedef void (*nekokem_desktop_progress)(uint64_t processed, uint64_t total);

// Blocks until Core finishes; call it off the main thread. The reply is freed
// with nekokem_desktop_free.
char *nekokem_desktop_call(const uint8_t *request, size_t length, nekokem_desktop_progress progress);
void nekokem_desktop_free(char *reply);
// 1 when the running encryption or decryption with this id was asked to stop.
int nekokem_desktop_cancel(const char *id);
// 1 while the window must stay open for Core to finish; 0 when it may close.
int nekokem_desktop_defer_close(void);
int nekokem_desktop_close_ready(void);
#endif
