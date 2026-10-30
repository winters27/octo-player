/* An independent, deliberately slow clipboard owner for paste/focus races. */
#include <wayland-client.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include "octo-data-control.h"

static struct ext_data_control_manager_v1 *manager;
static struct wl_seat *seat;
static const char *text;
static const char *gate;
static void send_data(void *d, struct ext_data_control_source_v1 *s, const char *mime, int fd) {
    puts("READ"); fflush(stdout);
    usleep(750000);
    /* The harness releases this gate only after observing the other field's
     * IME context. A busy compositor must not turn the race into a timing bet. */
    for (unsigned waits = 0; gate && access(gate, F_OK) == 0; waits++) {
        if (waits == 1000) { fputs("Clipboard gate timed out\n", stderr); exit(5); }
        usleep(10000);
    }
    ssize_t sent = write(fd, text, strlen(text));
    close(fd);
    printf("SENT bytes=%zd\n", sent); fflush(stdout);
}
static void cancelled(void *d, struct ext_data_control_source_v1 *s) { puts("CANCELLED"); fflush(stdout); }
static const struct ext_data_control_source_v1_listener source_listener = {send_data, cancelled};
static void offer_mime(void *d, struct ext_data_control_offer_v1 *o, const char *mime) {}
static const struct ext_data_control_offer_v1_listener offer_listener = {offer_mime};
static void offer(void *d, struct ext_data_control_device_v1 *dev, struct ext_data_control_offer_v1 *o) {
    ext_data_control_offer_v1_add_listener(o, &offer_listener, NULL);
}
static void selection(void *d, struct ext_data_control_device_v1 *dev, struct ext_data_control_offer_v1 *o) {
    if (o) ext_data_control_offer_v1_destroy(o);
}
static void finished(void *d, struct ext_data_control_device_v1 *dev) {}
static const struct ext_data_control_device_v1_listener device_listener = {offer, selection, finished, selection};
static void global(void *d, struct wl_registry *r, unsigned id, const char *name, unsigned version) {
    if (!strcmp(name, "ext_data_control_manager_v1"))
        manager = wl_registry_bind(r, id, &ext_data_control_manager_v1_interface, 1);
    if (!strcmp(name, "wl_seat")) seat = wl_registry_bind(r, id, &wl_seat_interface, 1);
}
static void removed(void *d, struct wl_registry *r, unsigned id) {}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    text = argv[1];
    gate = getenv("OCTO_CLIPBOARD_GATE");
    signal(SIGPIPE, SIG_IGN);
    struct wl_display *display = wl_display_connect(NULL);
    if (!display) return 3;
    struct wl_registry *registry = wl_display_get_registry(display);
    const struct wl_registry_listener listener = {global, removed};
    wl_registry_add_listener(registry, &listener, NULL);
    wl_display_roundtrip(display);
    if (!manager || !seat) { fputs("No ext-data-control manager or seat\n", stderr); return 4; }
    struct ext_data_control_device_v1 *device = ext_data_control_manager_v1_get_data_device(manager, seat);
    ext_data_control_device_v1_add_listener(device, &device_listener, NULL);
    struct ext_data_control_source_v1 *source = ext_data_control_manager_v1_create_data_source(manager);
    ext_data_control_source_v1_add_listener(source, &source_listener, NULL);
    ext_data_control_source_v1_offer(source, "text/plain;charset=utf-8");
    ext_data_control_device_v1_set_selection(device, source);
    wl_display_roundtrip(display);
    puts("READY"); fflush(stdout);
    while (wl_display_dispatch(display) >= 0) {}
    wl_display_disconnect(display);
    return 0;
}
