"""A local Subsonic library and real PCM stream for native-window acceptance."""
import hashlib
import io
import json
import struct
import threading
import wave
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


def cover():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + b''.join(bytes((x // 2, y // 2, 180, 255)) for x in range(512)) for y in range(512))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 512, 512, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


class Fixture:
    def __init__(self):
        self.calls = []
        self.songs = [dict(id=f's{i}', title=title, artist='Wayland Ensemble', artistId='r1', album='Native Sessions',
                           albumId='a1', duration=90, suffix='wav', contentType='audio/wav', coverArt='cover', track=i)
                      for i, title in enumerate(['Native Sunrise', 'Native Night', 'Wayland Waltz'], 1)]
        self.album = dict(id='a1', name='Native Sessions', artist='Wayland Ensemble', artistId='r1', songCount=3, duration=270, coverArt='cover')
        self.picture = cover()
        data = io.BytesIO()
        with wave.open(data, 'wb') as wav:
            wav.setnchannels(2)
            wav.setsampwidth(2)
            wav.setframerate(48000)
            wav.writeframes(bytes(48000 * 4 * 90))
        self.audio = data.getvalue()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                url = urlparse(self.path)
                endpoint = url.path.rsplit('/', 1)[-1].removesuffix('.view')
                query = {key: value[0] for key, value in parse_qs(url.query, keep_blank_values=True).items()}
                fixture.calls.append((endpoint, query.get('query', ''), query.get('id', '')))
                token = hashlib.md5(('secret' + query.get('s', '')).encode()).hexdigest()
                if query.get('u') != 'listener' or query.get('t') != token:
                    body = dict(status='failed', version='1.16.1', error=dict(code=40, message='Wrong test credentials'))
                elif endpoint in ('stream', 'download'):
                    return self.binary(fixture.audio, 'audio/wav', ranges=True)
                elif endpoint == 'getCoverArt':
                    return self.binary(fixture.picture, 'image/png')
                else:
                    body = dict(status='ok', version='1.16.1', type='navidrome', serverVersion='0.58.0', openSubsonic=True)
                    albums = [] if int(query.get('offset', 0)) else [fixture.album]
                    songs = [] if int(query.get('songOffset', 0)) else fixture.songs
                    payloads = {
                        'getOpenSubsonicExtensions': dict(openSubsonicExtensions=[]),
                        'getAlbumList2': dict(albumList2=dict(album=albums)),
                        'getArtists': dict(artists=dict(index=[dict(name='W', artist=[dict(id='r1', name='Wayland Ensemble', albumCount=1)])])),
                        'getAlbum': dict(album=dict(fixture.album, song=fixture.songs)),
                        'getArtist': dict(artist=dict(id='r1', name='Wayland Ensemble', album=[fixture.album])),
                        'getMusicFolders': dict(musicFolders=dict(musicFolder=[dict(id=1, name='Music')])),
                        'getPlaylists': dict(playlists=dict(playlist=[])),
                        'getStarred2': dict(starred2={}),
                        'getScanStatus': dict(scanStatus=dict(scanning=False, count=3)),
                        'getPlayQueue': dict(playQueue=dict(entry=[])),
                        'getGenres': dict(genres=dict(genre=[])),
                        'search3': dict(searchResult3=dict(
                            song=[s for s in songs if query.get('query', '').casefold() in s['title'].casefold()],
                            album=[a for a in albums if query.get('query', '').casefold() in a['name'].casefold()], artist=[])),
                        'getRandomSongs': dict(randomSongs=dict(song=fixture.songs)),
                        'getInternetRadioStations': dict(internetRadioStations=dict(internetRadioStation=[])),
                        'getLyricsBySongId': dict(lyricsList=dict(structuredLyrics=[])),
                    }
                    body.update(payloads.get(endpoint, {}))
                self.binary(json.dumps({'subsonic-response': body}).encode(), 'application/json')

            def binary(self, data, content_type, ranges=False):
                start, end = 0, len(data) - 1
                partial = ranges and self.headers.get('Range', '').startswith('bytes=')
                if partial:
                    first, last = self.headers['Range'][6:].split('-', 1)
                    start, end = int(first or 0), min(int(last or end), end)
                self.send_response(206 if partial else 200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(end - start + 1))
                if ranges:
                    self.send_header('Accept-Ranges', 'bytes')
                if partial:
                    self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
                self.end_headers()
                try:
                    self.wfile.write(data[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.address = f'http://127.0.0.1:{self.server.server_port}'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
