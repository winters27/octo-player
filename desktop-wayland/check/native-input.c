#include <wayland-client.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "octo-fake-input.h"
static struct org_kde_kwin_fake_input *input;
static void global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
    if (strcmp(interface, "org_kde_kwin_fake_input") == 0)
        input = wl_registry_bind(registry, name, &org_kde_kwin_fake_input_interface, version < 6 ? version : 6);
}
static void removed(void *data, struct wl_registry *registry, uint32_t name) {}
static int event(struct wl_display *display, const char *value) {
    unsigned a, b;
    if (sscanf(value, "pause=%u", &a) == 1) { struct timespec t = {a / 1000, (a % 1000) * 1000000}; nanosleep(&t, NULL); }
    else if (sscanf(value, "move=%u,%u", &a, &b) == 2) org_kde_kwin_fake_input_pointer_motion_absolute(input, wl_fixed_from_int(a), wl_fixed_from_int(b));
    else if (sscanf(value, "button=%u,%u", &a, &b) == 2) org_kde_kwin_fake_input_button(input, a, b);
    else if (sscanf(value, "key=%u,%u", &a, &b) == 2) org_kde_kwin_fake_input_keyboard_key(input, a, b);
    else if (sscanf(value, "sym=%u,%u", &a, &b) == 2) org_kde_kwin_fake_input_keyboard_keysym(input, a, b);
    else { fprintf(stderr, "Unknown input %s\n", value); return 4; }
    return wl_display_roundtrip(display) < 0 ? 5 : 0;
}
int main(int argc, char **argv) {
    struct wl_display *d = wl_display_connect(NULL);
    if (!d) return 2;
    struct wl_registry *registry = wl_display_get_registry(d);
    const struct wl_registry_listener listener = {global, removed};
    wl_registry_add_listener(registry, &listener, NULL);
    wl_display_roundtrip(d);
    if (!input) { fprintf(stderr, "No KWin fake-input protocol\n"); return 3; }
    org_kde_kwin_fake_input_authenticate(input, "Octo Wayland acceptance", "Verify keyboard and pointer input in an isolated test session");
    wl_display_roundtrip(d);
    if (argc == 2 && !strcmp(argv[1], "--stdin")) {
        /* Keep one virtual keyboard/pointer for the whole acceptance session;
         * per-chord device creation/removal introduces unrelated focus/grabs. */
        char *line = NULL;
        size_t capacity = 0;
        while (getline(&line, &capacity, stdin) >= 0) {
            for (char *value = strtok(line, " \t\r\n"); value; value = strtok(NULL, " \t\r\n")) {
                int status = event(d, value);
                if (status) return status;
            }
            puts("DONE"); fflush(stdout);
        }
        free(line);
    } else for (int i = 1; i < argc; i++) {
        int status = event(d, argv[i]);
        if (status) return status;
    }
    wl_display_disconnect(d);
    return 0;
}
