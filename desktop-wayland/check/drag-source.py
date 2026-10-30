#!/usr/bin/python3
"""Independent GTK4 source for a real Wayland URI-list drag into the player."""
import os
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, Gdk, GLib
app = Gtk.Application(application_id='app.winters.octo.drag-source')


def activate(app):
    window = Gtk.ApplicationWindow(application=app, title='Octo DnD test source')
    window.set_default_size(300, 160)
    label = Gtk.Label(label='Drag the audio file to Octo')
    drag = Gtk.DragSource()
    drag.set_actions(Gdk.DragAction.COPY)
    def prepare(source, x, y):
        print(f'PREPARE x={x} y={y}', flush=True)
        return Gdk.ContentProvider.new_for_bytes(
            'text/uri-list', GLib.Bytes.new((os.environ['OCTO_DROP_URI'] + '\r\n').encode()))
    drag.connect('prepare', prepare)
    drag.connect('drag-begin', lambda *args: print('BEGIN', flush=True))
    drag.connect('drag-end', lambda source, drag, delete: print(f'END delete={delete}', flush=True))
    def cancel(source, drag, reason):
        print(f'CANCEL reason={reason}', flush=True)
        return False
    drag.connect('drag-cancel', cancel)
    label.add_controller(drag)
    window.set_child(label)
    window.connect('map', lambda *args: print('MAPPED', flush=True))
    window.present()


app.connect('activate', activate)
app.run()
