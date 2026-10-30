/* A deterministic input method for the isolated compositor acceptance test.
 * KWin translates input-method-v1 requests into the player's text-input-v3.
 * No keyboard grab: ordinary hardware input is tested independently.
 */
#include <wayland-client.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "octo-input-method.h"

static struct zwp_input_method_context_v1 *context;
static unsigned serial;
static void surrounding(void *d, struct zwp_input_method_context_v1 *c, const char *text, unsigned cursor, unsigned anchor) {
    /* Hex keeps Unicode/newlines unambiguous and gives the harness real field
     * readiness instead of sleeping after an asynchronous clipboard request. */
    fputs("octo-ime: surrounding=", stdout);
    for (const unsigned char *p = (const unsigned char *)text; *p; p++) printf("%02x", *p);
    printf(" cursor=%u anchor=%u\n", cursor, anchor); fflush(stdout);
}
static void reset(void *d, struct zwp_input_method_context_v1 *c) {}
static void content(void *d, struct zwp_input_method_context_v1 *c, unsigned hint, unsigned purpose) {}
static void action(void *d, struct zwp_input_method_context_v1 *c, unsigned button, unsigned index) {}
static void state(void *d, struct zwp_input_method_context_v1 *c, unsigned value) { serial = value; }
static void language(void *d, struct zwp_input_method_context_v1 *c, const char *value) {}
static const struct zwp_input_method_context_v1_listener listener = {surrounding, reset, content, action, state, language};
static void activate(void *d, struct zwp_input_method_v1 *im, struct zwp_input_method_context_v1 *c) {
    context = c;
    zwp_input_method_context_v1_add_listener(c, &listener, NULL);
    puts("octo-ime: active"); fflush(stdout);
}
static void deactivate(void *d, struct zwp_input_method_v1 *im, struct zwp_input_method_context_v1 *c) {
    if (context == c) context = NULL;
    zwp_input_method_context_v1_destroy(c);
}
static const struct zwp_input_method_v1_listener im_listener = {activate, deactivate};
static void global(void *d, struct wl_registry *r, unsigned id, const char *name, unsigned version) {
    if (!strcmp(name, "zwp_input_method_v1")) {
        struct zwp_input_method_v1 *im = wl_registry_bind(r, id, &zwp_input_method_v1_interface, 1);
        zwp_input_method_v1_add_listener(im, &im_listener, NULL);
    }
}
static void removed(void *d, struct wl_registry *r, unsigned id) {}
static void command(char *line) {
    if (!context) { fputs("octo-ime: no active context\n", stderr); return; }
    int before, after;
    if (!strncmp(line, "preedit=", 8)) {
        zwp_input_method_context_v1_preedit_cursor(context, strlen(line + 8));
        zwp_input_method_context_v1_preedit_string(context, serial, line + 8, "");
    } else if (!strncmp(line, "commit=", 7)) {
        zwp_input_method_context_v1_commit_string(context, serial, line + 7);
    } else if (sscanf(line, "delete=%d,%d", &before, &after) == 2) {
        zwp_input_method_context_v1_delete_surrounding_text(context, -before, before + after);
    }
}
int main(void) {
    const char *log = getenv("OCTO_IME_LOG");
    if (log && !freopen(log, "w", stdout)) return 5;
    const char *path = getenv("OCTO_IME_CONTROL");
    if (!path) return 2;
    struct wl_display *display = wl_display_connect(NULL);
    if (!display) return 3;
    struct wl_registry *registry = wl_display_get_registry(display);
    const struct wl_registry_listener globals = {global, removed};
    wl_registry_add_listener(registry, &globals, NULL);
    wl_display_roundtrip(display);
    mkfifo(path, 0600);
    int control = open(path, O_RDWR | O_NONBLOCK);
    if (control < 0) return 4;
    struct pollfd fds[] = {{wl_display_get_fd(display), POLLIN, 0}, {control, POLLIN, 0}};
    char buffer[4096]; size_t used = 0;
    while (1) {
        wl_display_dispatch_pending(display);
        wl_display_flush(display);
        if (poll(fds, 2, -1) < 0) break;
        if (fds[0].revents & (POLLHUP | POLLERR)) break;
        if ((fds[0].revents & POLLIN) && wl_display_dispatch(display) < 0) break;
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(control, buffer + used, sizeof(buffer) - used - 1);
            if (n <= 0) continue;
            used += n; buffer[used] = 0;
            char *line = buffer, *end;
            while ((end = strchr(line, '\n'))) { *end = 0; command(line); line = end + 1; }
            used -= line - buffer; memmove(buffer, line, used);
        }
    }
    close(control); unlink(path); wl_display_disconnect(display);
    return 0;
}
