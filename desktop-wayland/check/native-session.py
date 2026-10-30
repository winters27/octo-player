#!/usr/bin/python3
"""Run inside the isolated KWin session described in README.md."""
import json
import atexit
import os
import re
import select
import signal
from pathlib import Path
import shutil
import subprocess
import time
from fixture_server import Fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(os.environ.get('OCTO_CHECK_OUT', '/tmp/opencode/octo-native-player-check'))
OUT.mkdir(parents=True, exist_ok=True)
assert os.environ.get('WAYLAND_DISPLAY', '').startswith('wayland-octo'), 'Use an isolated test compositor'
INPUT = os.environ.get('OCTO_CHECK_INPUT', '/tmp/opencode/octo-input')
LAUNCHER = os.environ.get('OCTO_WAYLAND_LAUNCHER', str(ROOT / 'desktop-wayland/build/install/Octo-Wayland/octo-wayland'))
input_process = None


def run(*args, **kwargs):
    p = subprocess.run(args, text=True, capture_output=True, timeout=20, **kwargs)
    if p.returncode:
        raise RuntimeError(f'{args}: {p.stderr} {p.stdout}')
    return p.stdout.strip()


def wait(test, message, seconds=40):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if test():
            return
        time.sleep(.1)
    raise AssertionError(message)


def script(body):
    path = OUT / 'window.js'
    marker = f'OCTO-SCRIPT-{time.time_ns()}'
    path.write_text(body + '\nconsole.warn(' + json.dumps(marker) + ');')
    number = run('qdbus6', 'org.kde.KWin', '/Scripting', 'org.kde.kwin.Scripting.loadScript', str(path), 'octo-check')
    paths = run('qdbus6', 'org.kde.KWin').splitlines()
    obj = next(p.strip() for p in paths if p.strip().endswith('/Script' + number) or p.strip() == '/' + number)
    run('qdbus6', 'org.kde.KWin', obj, 'org.kde.kwin.Script.run')
    # run() starts an asynchronous script load. Do not unload it until the
    # compositor has actually completed the requested window operations.
    wait(lambda: marker in (OUT / 'compositor.log').read_text(), 'KWin script did not complete')
    run('qdbus6', 'org.kde.KWin', '/Scripting', 'org.kde.kwin.Scripting.unloadScript', 'octo-check')


def move(x=30, y=30, width=1100, height=830):
    script(f'''for (const w of workspace.windowList()) if (String(w.resourceClass) === "app.winters.octo.wayland") {{
        w.frameGeometry = {{x:{x}, y:{y}, width:{width}, height:{height}}};
        workspace.activeWindow = w;
    }}''')
    time.sleep(.5)


def windows():
    marker = f'OCTO-WINDOWS-{time.time_ns()}'
    script('console.warn(' + json.dumps(marker) + '+JSON.stringify(workspace.windowList().map(w => ({'
           'app:String(w.resourceClass),caption:String(w.caption),active:workspace.activeWindow===w,'
           'minimized:w.minimized,frame:w.frameGeometry,client:w.clientGeometry}))));')
    return json.loads((OUT / 'compositor.log').read_text().rsplit(marker, 1)[1].splitlines()[0])


def player_window():
    found = [w for w in windows() if w['app'] == 'app.winters.octo.wayland']
    return found[0] if len(found) == 1 else None


def maximize(value):
    flag = 'true' if value else 'false'
    script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") '
           + f'w.setMaximize({flag},{flag});')


def input_command(*events):
    global input_process
    if input_process is None:
        with (OUT / 'input.log').open('w') as log:
            input_process = subprocess.Popen([INPUT, '--stdin'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                             stderr=log, text=True, start_new_session=True)
        atexit.register(stop, input_process)
    with (OUT / 'input-commands.log').open('a') as log:
        log.write(f'{time.monotonic():.6f} ' + ' '.join(events) + '\n')
    input_process.stdin.write(' '.join(events) + '\n')
    input_process.stdin.flush()
    assert select.select([input_process.stdout], [], [], 20)[0], 'Input driver did not acknowledge the command'
    assert input_process.stdout.readline() == 'DONE\n', 'Input driver exited; see input.log'


def input(*events):
    input_command(*[value for event in events for value in (event, 'pause=50')])


def key(code):
    input_command(f'key={code},1', 'pause=20', f'key={code},0', 'pause=50')


def ctrl(code):
    chord(29, code)


def chord(modifier, code):
    input_command(f'key={modifier},1', 'pause=30', f'key={code},1', 'pause=20',
        f'key={code},0', 'pause=30', f'key={modifier},0', 'pause=50')


def copied_text(expected):
    last = None
    def matches():
        nonlocal last
        result = subprocess.run(['wl-paste', '--no-newline'], text=True, capture_output=True, timeout=5)
        last = result.stdout if result.returncode == 0 else result.stderr
        return result.returncode == 0 and result.stdout == expected
    try:
        wait(matches, f'Clipboard did not receive {expected!r}')
    except AssertionError:
        capture('clipboard-failed')
        raise AssertionError(f'Expected clipboard {expected!r}, got {last!r}') from None


def stop(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def type_text(text):
    for char in text:
        sym = ord(char) if ord(char) < 256 else 0x1000000 + ord(char)
        input_command(f'sym={sym},1', 'pause=20', f'sym={sym},0', 'pause=50')


def paste(text):
    subprocess.run(['wl-copy', '--type', 'text/plain;charset=utf-8', text], check=True, timeout=10,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    copied_text(text)  # The external owner has really published the offer.
    ctrl(47)


def field_state():
    path = Path(os.environ['OCTO_IME_LOG'])
    entries = re.findall(r'octo-ime: surrounding=([0-9a-f]*) cursor=(\d+) anchor=(\d+)', path.read_text() if path.exists() else '')
    return (bytes.fromhex(entries[-1][0]).decode(), int(entries[-1][1]), int(entries[-1][2])) if entries else None


def field_text():
    state = field_state()
    return state[0] if state else None


def select_all():
    ctrl(30)
    wait(lambda: (s := field_state()) is not None and {s[1], s[2]} == {0, len(s[0].encode())},
         'Ctrl+A did not select the focused field')


def replace_field(text, expected=None):
    select_all()
    # Even when the old value equals the new one, observe a real transition.
    # Otherwise this helper can return with its asynchronous paste still pending.
    key(14)
    wait(lambda: field_text() == '', 'Field was not cleared before replacement')
    paste(text)
    expected = text if expected is None else expected
    try:
        wait(lambda: field_text() == expected, f'Field did not receive {expected!r}; IME surrounding text differs')
    except AssertionError:
        capture('field-failed')
        raise


def clipboard_roundtrip(text):
    replace_field(text)
    # The expected copy must differ from the externally seeded clipboard.
    # Observe real text-input state before typing, not an assumed transfer time.
    marker = '-checked'
    key(107)
    type_text(marker)
    wait(lambda: field_text() == text + marker, 'Keyboard marker did not reach the pasted field')
    select_all()
    ctrl(46)
    copied_text(text + marker)
    replace_field(text)
    select_all()


def queued_keyboard():
    # Independent V2 control/burst: no IME preedit, no pause or reselect between
    # Backspace and Ctrl+A, and a different clipboard seed before the real copy.
    for separated in (True, False):
        replace_field('saved')
        key(107)
        wait(lambda: field_state() == ('saved', 5, 5), 'Queued-key setup caret not ready')
        sentinel = 'queued-key-copy-sentinel'
        subprocess.run(['wl-copy', '--type', 'text/plain;charset=utf-8', sentinel], check=True, timeout=10)
        copied_text(sentinel)
        if separated:
            key(14)
            select_all()
        else:
            input_command('key=14,1', 'key=14,0', 'key=29,1', 'key=30,1', 'key=30,0', 'key=29,0')
        wait(lambda: field_state() == ('save', 4, 0), 'Backspace/Ctrl+A restored deleted text or lost selection')
        ctrl(46)
        copied_text('save')
    capture('keyboard-burst')
    print('PASS queued Backspace/Ctrl+A: save selected 0..4 and independently copied, with no preedit', flush=True)


def delayed_paste_focus():
    for shortcut in ('ctrl-v', 'shift-insert'):
        for focus in ('tab', 'click'):
            # The sign-in server and username fields are different Compose
            # selection managers, in the same native window.
            click(550, 443)
            replace_field('target')
            click(550, 357)
            select_all()
            key(14)
            wait(lambda: field_text() == '', 'Original paste field was not cleared')
            name = f'{shortcut}-{focus}'
            log_path = OUT / f'clipboard-{name}.log'
            gate = OUT / f'clipboard-{name}.hold'
            owner = None
            try:
                with log_path.open('w') as log:
                    owner = subprocess.Popen([os.environ['OCTO_DELAYED_CLIPBOARD'], 'delayed-' + name],
                                             env=dict(os.environ, OCTO_CLIPBOARD_GATE=str(gate)),
                                             stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                    wait(lambda: 'SENT ' in log_path.read_text(), 'Clipboard offer prefetch did not finish')
                    reads = log_path.read_text().splitlines().count('READ')
                    gate.touch()
                    if shortcut == 'ctrl-v':
                        ctrl(47)
                    else:
                        chord(42, 110)
                    wait(lambda: log_path.read_text().splitlines().count('READ') > reads, 'Paste did not request external data')
                    if focus == 'tab':
                        key(15)
                    else:
                        click(550, 443)
                    wait(lambda: field_text() == 'target', 'Delayed paste focus did not move to the target field')
                    assert log_path.read_text().count('SENT ') == reads, 'Clipboard replied before focus changed'
                    gate.unlink()
                    wait(lambda: log_path.read_text().count('SENT ') > reads, 'Delayed clipboard did not reply')
                    select_all()
                    ctrl(46)
                    copied_text('target')
                    click(550, 357)
                    select_all()
                    ctrl(46)
                    copied_text('delayed-' + name)
            except Exception:
                capture('delayed-failed')
                raise
            finally:
                gate.unlink(missing_ok=True)
                stop(owner)
    click(550, 443)
    ctrl(30)
    key(14)
    click(550, 357)
    ctrl(30)
    print('PASS delayed Ctrl+V/Shift+Insert stay on the original field across Tab/click', flush=True)


def click(x, y):
    input(f'move={x},{y}', 'button=272,1', 'button=272,0')


def capture(name):
    image = OUT / 'frame.png'
    before = image.stat().st_mtime_ns if image.exists() else 0
    key(88)  # F12: explicit diagnostic capture property only.
    wait(lambda: image.exists() and image.stat().st_mtime_ns != before, 'No GPU readback')
    shutil.copyfile(image, OUT / (name + '.png'))


def media(property):
    return run('qdbus6', 'org.mpris.MediaPlayer2.octo', '/org/mpris/MediaPlayer2',
               'org.freedesktop.DBus.Properties.Get', 'org.mpris.MediaPlayer2.Player', property)


def ime(command):
    with open(os.environ['OCTO_IME_CONTROL'], 'w') as pipe:
        pipe.write(command + '\n')
    time.sleep(.4)


def ime_focus_roundtrip(expected, name):
    log = lambda: (OUT / 'player.log').read_text()
    before = len(log())
    script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.drag-source") workspace.activeWindow=w;')
    wait(lambda: 'focus id=1 focused=false' in log()[before:], 'IME check did not lose native focus')
    before = len(log())
    script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") workspace.activeWindow=w;')
    wait(lambda: 'focus id=1 focused=true' in log()[before:], 'IME check did not regain native focus')
    wait(lambda: field_text() == expected, f'{name}: committed text changed on leave')
    # A stale externally seeded clipboard must not satisfy the copy assertion.
    sentinel = 'ime-copy-sentinel-' + name
    subprocess.run(['wl-copy', '--type', 'text/plain;charset=utf-8', sentinel], check=True, timeout=10)
    copied_text(sentinel)
    select_all()
    ctrl(46)
    copied_text(expected)
    capture(name)
    print(f'PASS IME {name}: retained {expected!r} across native window leave', flush=True)


def portal_ready(title):
    # Observe the actual mapped dialog, rather than type after an arbitrary
    # delay into whichever application happens to hold keyboard focus.
    marker = f'OCTO-DIALOG-{time.time_ns()}'
    def mapped():
        script('for (const w of workspace.windowList()) if (String(w.caption).includes('
               + json.dumps(title) + ')) {workspace.activeWindow=w; console.warn(' + json.dumps(marker) + ');}')
        return marker in (OUT / 'compositor.log').read_text()
    wait(mapped, f'No isolated portal dialog: {title}')


def check_portals(env):
    target = OUT / f'Portal Unicode Файл-{time.time_ns()}.txt'
    process = None
    try:
        with (OUT / 'portal.log').open('w') as log:
            process = subprocess.Popen([LAUNCHER], env=dict(env, JAVA_TOOL_OPTIONS='-Docto.wayland.checkFiles=true'),
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            portal_text = lambda: (OUT / 'portal.log').read_text()
            wait(lambda: 'check-files save' in portal_text(), 'No portal save request')
            portal_ready('Octo check: Save')
            ctrl(30)
            paste(str(target))
            key(28)
            wait(target.exists, 'Portal Save did not return the selected file')
            wait(lambda: 'check-files open' in portal_text(), 'No portal open request')
            portal_ready('Octo check: Open')
            ctrl(30)  # The native file dialog initially focuses its filename.
            paste(str(target))
            key(28)
            wait(lambda: 'check-files PASS' in portal_text(), 'Portal Open did not return the saved Unicode path')
            assert process.wait(timeout=15) == 0
            print('PASS real portal Save/Open roundtrip with a Unicode filename', flush=True)
    finally:
        stop(process)


if os.environ.get('OCTO_CHECK_PORTAL_ONLY') == '1':
    check_portals(dict(os.environ, OCTO_PROFILE_DIR=str(OUT / f'portal-profile-{time.time_ns()}')))
    print('PASS native acceptance complete', flush=True)
    raise SystemExit(0)


fixture = Fixture()
process = None
source = None
try:
    outputs = json.loads(run('kscreen-doctor', '-j'))['outputs']
    a, b = [str(o['id']) for o in outputs]
    run('kscreen-doctor', f'output.{a}.scale.1', f'output.{a}.position.0,0', f'output.{b}.scale.1.5', f'output.{b}.position.1280,0')
    profile = OUT / f'profile-{time.time_ns()}'
    env = dict(os.environ, OCTO_PROFILE_DIR=str(profile), JAVA_HOME=os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-27-openjdk'),
               JAVA_TOOL_OPTIONS=f'-Docto.wayland.capture={OUT}/frame.png -Docto.wayland.seconds=360', GTK_A11Y='none', NO_AT_BRIDGE='1')
    env.pop('DISPLAY', None)
    if os.environ.get('OCTO_CHECK_WIRE') == '1':
        env['WAYLAND_DEBUG'] = 'client'
    with (OUT / 'player.log').open('w') as log:
        command = [LAUNCHER]
        if os.environ.get('OCTO_CHECK_TRACE') == '1':
            command = ['strace', '-f', '-e', 'trace=connect,openat', '-o', str(OUT / 'trace.log'), *command]
        (OUT / 'frame.png').unlink(missing_ok=True)
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        text = lambda: (OUT / 'player.log').read_text()
        wait(lambda: 'player-ready audio=native' in text() and 'wayland: gpu=' in text()
             and 'focus id=1 focused=true' in text() and 'wayland: captured=' in text(),
             'No mapped, focused GPU window', seconds=70)
        move()
        wait(lambda: 'configure id=1 size=1100x802' in text(), 'Initial window resize was not acknowledged')
        capture('initial')
        ctrl(30)  # Select the server address.
        for k in (24, 46, 20, 24):
            key(k)
        key(14)
        ctrl(30)
        ctrl(46)
        copied_text('oct')
        print('PASS real Compose text field: keyboard, backspace, selection and copy', flush=True)
        queued_keyboard()
        clipboard_roundtrip('Привет 🌊')
        print('PASS real Compose UTF-8 clipboard roundtrip', flush=True)
        if os.environ.get('OCTO_DELAYED_CLIPBOARD') and os.environ.get('OCTO_CHECK_IME_ONLY') != '1':
            delayed_paste_focus()
        if os.environ.get('OCTO_IME_CONTROL'):
            key(14)
            ime('preedit=に')
            capture('ime-preedit')
            ime('preedit=日本🌊')
            ime('commit=日本🌊')
            ctrl(30)
            ctrl(46)
            copied_text('日本🌊')
            key(107)  # End.
            ime('delete=4,0')
            ime('commit=語')
            ctrl(30)
            ctrl(46)
            copied_text('日本語')
            print('PASS real text-input-v3 preedit, commit and UTF-8 deletion', flush=True)
            key(107)
            ime('preedit=未確定')
            capture('ime-before-leave')
            with (OUT / 'ime-focus-source.log').open('w') as focus_log:
                source = subprocess.Popen(['/usr/bin/python3', str(ROOT / 'desktop-wayland/check/drag-source.py')],
                                          env=env, stdout=focus_log, stderr=subprocess.STDOUT, start_new_session=True)
                wait(lambda: any(w['app']=='app.winters.octo.drag-source' and w['active'] for w in windows()), 'No independent window for IME leave')
                wait(lambda: 'focus id=1 focused=false' in text(), 'IME leave did not lose native focus')
                script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") workspace.activeWindow=w;')
                ctrl(30)
                ctrl(46)
                copied_text('日本語')
                print('PASS IME window leave cancels only uncommitted preedit', flush=True)

                # Keyboard/selection/leave with real protocol transport. KWin
                # replays cached preedit on context commits, so this non-grabbing
                # IME must explicitly end it after the hardware edit. The exact
                # no-further-IME-event V1 ordering is in ModernTextInputTest.
                replace_field('saved')
                key(107)
                ime('preedit=に')
                capture('ime-v1-preedit')
                key(14)  # Compose Backspace removes に.
                ime('preedit=')
                select_all()
                assert field_text() == 'saved', 'Backspace did not remove only the preedit'
                ime_focus_roundtrip('saved', 'keyboard-selection-leave')

                replace_field('saved')
                key(107)
                ime('preedit=に')
                paste('pasted')
                wait(lambda: field_text() == 'savedにpasted', 'Clipboard did not finish composition')
                ime('preedit=')
                select_all()
                ime_focus_roundtrip('savedにpasted', 'clipboard-selection-leave')

                replace_field('saved')
                key(107)
                ime('preedit=に')
                ime('commit=日本')
                wait(lambda: field_text() == 'saved日本', 'IME commit did not reach the field')
                select_all()
                ime_focus_roundtrip('saved日本', 'commit-selection-leave')
                stop(source)
                source = None
        if os.environ.get('OCTO_CHECK_IME_ONLY') == '1':
            script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") w.closeWindow();')
            assert process.wait(timeout=15) == 0
            assert 'clean-exit' in text()
            print('PASS focused native IME acceptance', flush=True)
            print('PASS native acceptance complete', flush=True)
            raise SystemExit(0)
        # SignInForm separates the scheme into its own toggle when pasting URLs.
        replace_field(fixture.address, fixture.address.removeprefix('http://'))
        key(15)
        type_text('listener')
        key(15)
        type_text('secret')
        capture('signin')
        # Keep the fixture's password out of the desktop keyring.
        click(770, 596)
        click(710, 712)
        time.sleep(3)
        capture('signin-response')
        wait(lambda: any(e == 'getAlbumList2' for e, _, _ in fixture.calls), 'Sign-in/library loading did not reach the server')
        time.sleep(3)
        capture('library')
        print('PASS native sign-in and library HTTP requests', flush=True)
        ctrl(37)  # Ctrl+K.
        clipboard_roundtrip('Native')
        capture('search')
        wait(lambda: [q for e, q, _ in fixture.calls if e == 'search3'][-1:] == ['Native'], 'Server did not receive the restored search query Native')
        print('PASS search field input and actual server query', flush=True)
        key(28)
        wait(lambda: any(e == 'stream' for e, _, _ in fixture.calls), 'No real HTTP audio stream')
        wait(lambda: media('PlaybackStatus') == 'Playing', 'Native engine did not start playing')
        first = int(media('Position'))
        time.sleep(1.5)
        assert int(media('Position')) - first > 500_000
        capture('playing')
        print('PASS search -> real HTTP playback, advancing MPRIS clock', flush=True)
        # Open the full player by its cover in the player bar.
        click(420, 803)
        time.sleep(.5)
        capture('full-player')
        key(57)  # Space pauses outside a text field.
        wait(lambda: media('PlaybackStatus') == 'Paused', 'Space did not pause')
        position = int(media('Position'))
        time.sleep(.7)
        assert abs(int(media('Position')) - position) < 150_000, 'Paused audio clock kept advancing'
        if os.environ.get('OCTO_CHECK_PERFORMANCE') == '1':
            pid = int(re.search(r'pid=(\d+)', text()).group(1))
            def ticks():
                fields = Path(f'/proc/{pid}/stat').read_text().split()
                return int(fields[13]) + int(fields[14])
            before = ticks()
            start = time.monotonic()
            time.sleep(8)
            cpu = 100 * (ticks() - before) / os.sysconf('SC_CLK_TCK') / (time.monotonic() - start)
            (OUT / 'performance.json').write_text(json.dumps({'paused_full_player_cpu_percent_of_one_core': cpu}))
            print(f'INFO paused full player CPU: {cpu:.1f}% of one core', flush=True)
        key(57)
        wait(lambda: media('PlaybackStatus') == 'Playing', 'Space did not resume')
        position = int(media('Position'))
        key(106)  # Seek forward.
        wait(lambda: int(media('Position')) > position + 4_000_000, 'Arrow did not seek')
        print('PASS native pause/resume and seeking', flush=True)
        maximize(True)
        wait(lambda: 'fullscreen=false maximized=true' in text(), 'Maximize not acknowledged')
        ctrl(50)  # Ctrl+M.
        wait(lambda: 'closed id=1' in text(), 'Mini player did not replace main window')
        time.sleep(2)
        capture('mini')
        assert media('PlaybackStatus') == 'Playing'
        before = player_window()['client']
        x, y = int(before['x'] + before['width'] * .8), int(before['y'] + before['height'] * .5)
        input(f'move={x},{y}', 'button=272,1', f'move={x+80},{y+40}', 'button=272,0', f'move={x+85},{y+45}')
        wait(lambda: 'pointer-cancel id=2 buttons=1' in text(), 'Compositor move did not cancel the pressed Compose gesture')
        wait(lambda: player_window()['client']['x'] != before['x'], 'Mini background did not move the native window')
        after = player_window()['client']
        click(int(after['x'] + 210), int(after['y'] + 126))
        wait(lambda: media('PlaybackStatus') == 'Paused', 'Mini transport click after move did not pause')
        click(int(after['x'] + 210), int(after['y'] + 126))
        wait(lambda: media('PlaybackStatus') == 'Playing', 'Mini transport click after move did not resume')
        volume = float(media('Volume'))
        assert volume > 0
        click(int(after['x'] + 494), int(after['y'] + 126))
        wait(lambda: float(media('Volume')) == 0, 'Mini volume click after move did not mute')
        click(int(after['x'] + 494), int(after['y'] + 126))
        wait(lambda: abs(float(media('Volume')) - volume) < .001, 'Mini volume click after move did not restore volume')
        print('PASS mini move cancels old gesture; transport and volume buttons work afterward', flush=True)
        key(1)
        wait(lambda: 'closed id=2' in text(), 'Mini player did not close')
        wait(lambda: 'focus id=3 focused=true' in text(), 'Main window did not regain focus')
        wait(lambda: re.search(r'configure id=3 .*maximized=true', text()), 'Main window lost maximization across mini mode')
        before_restore = len(text())
        maximize(False)
        wait(lambda: 'configure id=3 size=1100x802 fullscreen=false maximized=false' in text()[before_restore:], 'Normal bounds were replaced by maximized geometry')
        changes = text().count('rawScale=1.5')
        move(1320, 30, 800, 750)
        wait(lambda: text().count('rawScale=1.5') > changes, 'No mixed-scale transition')
        capture('scaled')
        move()
        key(87)  # F11.
        wait(lambda: 'fullscreen=true' in text(), 'Fullscreen not acknowledged')
        capture('fullscreen')
        key(87)
        print('PASS mini player, mixed scale and fullscreen', flush=True)
        move(30, 30, 780, 830)
        audio_file = OUT / 'Dropped Native Audio.wav'
        audio_file.write_bytes(fixture.audio)
        source_log = (OUT / 'drag-source.log').open('w')
        source = subprocess.Popen(['/usr/bin/python3', str(ROOT / 'desktop-wayland/check/drag-source.py')],
                                  env=dict(env, OCTO_DROP_URI=audio_file.as_uri()), stdout=source_log, stderr=subprocess.STDOUT, start_new_session=True)
        wait(lambda: 'MAPPED' in (OUT / 'drag-source.log').read_text(), 'No native GTK source window')
        marker = f'OCTO-DRAG-{time.time_ns()}'
        def source_ready():
            script('for (const w of workspace.windowList()) if (String(w.resourceClass) === "app.winters.octo.drag-source") {'
                   'w.frameGeometry={x:850,y:80,width:300,height:160}; workspace.activeWindow=w;'
                   'console.warn(' + json.dumps(marker) + '+JSON.stringify({caption:w.caption,frame:w.frameGeometry,client:w.clientGeometry}));}')
            log = (OUT / 'compositor.log').read_text()
            if marker not in log:
                return False
            client = json.loads(log.rsplit(marker, 1)[1].splitlines()[0])['client']
            return 840 <= client['x'] <= 880 and 80 <= client['y'] <= 140 and client['width'] > 0 and client['height'] > 0
        wait(source_ready, 'GTK source did not acknowledge its requested client geometry')
        geometry = json.loads((OUT / 'compositor.log').read_text().rsplit(marker, 1)[1].splitlines()[0])
        client = geometry['client']
        assert client['width'] > 0 and client['height'] > 0, geometry
        assert 840 <= client['x'] <= 880 and 80 <= client['y'] <= 140, geometry
        print(f'INFO matched GTK drag geometry: {geometry}', flush=True)
        x, y = int(client['x'] + client['width'] / 2), int(client['y'] + client['height'] / 2)
        input(f'move={x},{y}', 'button=272,1', *[f'move={n},{y}' for n in range(x - 10, 350, -20)], 'button=272,0')
        drag_text = lambda: (OUT / 'drag-source.log').read_text()
        wait(lambda: 'PREPARE ' in drag_text() and 'BEGIN' in drag_text(), 'GTK never began a drag')
        wait(lambda: re.search(r'wayland: drop id=\d+ mime=text/uri-list bytes=[1-9]\d*', text()), 'No native URI-list DropPerformed')
        wait(lambda: 'Dropped Native Audio' in media('Metadata'), 'Native file drop did not start playing')
        wait(lambda: media('PlaybackStatus') == 'Playing', 'Dropped audio did not play')
        wait(lambda: 'END ' in drag_text(), 'GTK did not complete the drag')
        assert 'CANCEL ' not in drag_text(), drag_text()
        print('PASS external GTK -> Wayland audio file drag/drop', flush=True)
        # Exercise the concern before changing activation code: mini is both
        # minimized and backgrounded behind an independent application.
        script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") workspace.activeWindow=w;')
        ctrl(50)
        wait(lambda: (player_window() or {}).get('caption', '').startswith('Octo mini'), 'No mini for Raise check')
        script('for (const w of workspace.windowList()) {'
               'if (String(w.resourceClass)==="app.winters.octo.wayland") w.minimized=true;'
               'if (String(w.resourceClass)==="app.winters.octo.drag-source") workspace.activeWindow=w;}')
        def backgrounded_mini():
            state = windows()
            return (any(w['app']=='app.winters.octo.drag-source' and w['active'] for w in state)
                    and any(w['app']=='app.winters.octo.wayland' and w['minimized'] and not w['active'] for w in state))
        wait(backgrounded_mini, 'Mini was not minimized behind the independent window')
        run('qdbus6', 'org.mpris.MediaPlayer2.octo', '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Raise')
        wait(lambda: (w := player_window()) and 'mini' not in w['caption'] and w['active'] and not w['minimized'], 'MPRIS Raise did not activate the full player from backgrounded/minimized mini')
        print('PASS MPRIS Raise from backgrounded/minimized mini', flush=True)
        stop(source)
        source_log.close()
        maximize(True)
        wait(lambda: (json.loads((profile / 'config/settings.json').read_text()).get('window') or {}).get('maximized') is True, 'Maximized flag was not persisted')
        script('for (const w of workspace.windowList()) if (String(w.resourceClass) === "app.winters.octo.wayland") w.closeWindow();')
        assert process.wait(timeout=15) == 0
        assert 'clean-exit' in text()
        if os.environ.get('OCTO_CHECK_TRACE') == '1':
            trace = (OUT / 'trace.log').read_text()
            assert 'wayland-octo-player-test' in trace and '/dev/dri/renderD' in trace
            assert not any('connect(' in line and ('X11-unix' in line or 'htons(6000)' in line) for line in trace.splitlines())
            print('PASS independent strace: Wayland + DRM render node, no X11 connection', flush=True)
        print('PASS native application clean shutdown', flush=True)
    with (OUT / 'restart.log').open('w') as log:
        process = subprocess.Popen([LAUNCHER], env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        restarted = lambda: (OUT / 'restart.log').read_text()
        wait(lambda: re.search(r'configure id=1 .*maximized=true', restarted()), 'Restart did not restore maximization')
        saved = json.loads((profile / 'config/settings.json').read_text())['window']
        before_restore = len(restarted())
        maximize(False)
        wait(lambda: f"size={int(saved['width'])}x{int(saved['height'])} fullscreen=false maximized=false" in restarted()[before_restore:], 'Restart lost normal dimensions')
        script('for (const w of workspace.windowList()) if (String(w.resourceClass)==="app.winters.octo.wayland") w.closeWindow();')
        assert process.wait(timeout=15) == 0
        print('PASS restart maximization and last normal dimensions', flush=True)
    if os.environ.get('OCTO_CHECK_PORTAL') == '1':
        check_portals(env)
    print('PASS native acceptance complete', flush=True)
finally:
    (OUT / 'server-calls.json').write_text(json.dumps(fixture.calls, indent=2))
    fixture.close()
    stop(source)
    stop(process)
