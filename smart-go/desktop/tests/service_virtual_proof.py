"""Real local KaTrain/KataGo and authenticated TLS with full-grid test observations."""
import json, os, ssl, sys, tempfile, threading, time, urllib.request, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from izis_katrain.launch import configure, create_app
runtime = Path(tempfile.mkdtemp(prefix='service-proof-', dir=ROOT / 'runtime'))
configure(runtime, ROOT / 'runtime/katago/squashfs-root/AppRun', 9, 8)
os.environ['KIVY_AUDIO'] = 'dummy'
from kivy.clock import Clock
from katrain.core.game import Move
from katrain.core.constants import PLAYER_HUMAN
app = create_app('127.0.0.1', 5000, 9, runtime, control_port=18767)
checks, errors = [], []

def exercise():
    try:
        pairing = json.loads((runtime / 'pairing/pairing.json').read_text())
        context = ssl.create_default_context(cafile=str(runtime / 'pairing/cert.pem'))
        context.check_hostname = False
        state, sequence, stream = {}, 0, uuid.uuid4().hex
        def call(op, **fields):
            nonlocal state
            command = dict(op=op, id=uuid.uuid4().hex, client='service-proof', game_id=state.get('game_id'),
                revision=state.get('revision'), controller_epoch=state.get('controller_epoch')) | fields
            req = urllib.request.Request('https://127.0.0.1:18767/v1/control', json.dumps(command).encode(),
                {'Authorization': 'Bearer ' + pairing['token'], 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, context=context, timeout=15) as response: result = json.load(response)
            if 'revision' in result: state = result
            return result
        def grid(stones):
            result = [0] * 361
            for x, y, color in stones: result[(y + 5) * 19 + x + 5] = 1 if color == 'B' else 2
            return result
        def observe(stones):
            nonlocal sequence
            for delay in (0, .7):
                time.sleep(delay); sequence += 1
                call('observe', physical=grid(stones), stream=stream, sequence=sequence)
        def passed(label):
            checks.append(label); print('PASS:', label, flush=True)
        call('acquire'); assert not state['active']
        call('new', size=9, color='B', mode='normal', difficulty='ai:default')
        observe([]); assert state['ready']
        passed('Authenticated 9x9 game and explicit full-grid reconciliation')
        stones = [[0, 1, 'B']]; observe(stones)
        result = call('physical_proposal', point=[0, 1], color='B', physical=grid(stones))
        assert result['proposal']['accepted'] and result['depth'] == 1
        passed('Physical proposal accepted without old equality gate')
        deadline = time.monotonic() + 90
        while state['depth'] < 2 and time.monotonic() < deadline:
            time.sleep(.5); sequence += 1
            call('observe', physical=grid(stones), stream=stream, sequence=sequence)
        assert state['depth'] == 2, state['status']
        observe(state['stones']); assert state['ready']
        passed('Real KataGo opponent move and placement confirmation')
        bad = state['stones'] + [[8, 8, 'W']]; observe(bad)
        result = call('physical_proposal', point=[8, 8], color='W', physical=grid(bad))
        assert not result['proposal']['accepted'] and state['depth'] == 2 and state['correction']['remove']
        passed('Rejected wrong-color proposal and correction')
        observe(state['stones']); done = threading.Event()
        def fixture(dt):
            for color in 'BW': app.gui.update_player(color, player_type=PLAYER_HUMAN)
            app.gui._do_new_game()
            app.gui.game.play(Move((0, 1), 'B')); app.gui.game.play(Move((0, 0), 'W')); done.set()
        Clock.schedule_once(fixture, 0); assert done.wait(10)
        call('status'); observe(state['stones'])
        stones = state['stones'] + [[1, 0, 'B']]; observe(stones)
        fields = dict(point=[1, 0], color='B', physical=grid(stones), id=uuid.uuid4().hex,
            game_id=state['game_id'], revision=state['revision'], controller_epoch=state['controller_epoch'])
        accepted = call('physical_proposal', **fields)
        assert accepted['proposal']['accepted'] and accepted['depth'] == 3
        assert [0, 0, 'W'] in accepted['correction']['remove']
        assert call('physical_proposal', **fields) == accepted
        assert not call('physical_proposal', **(fields | {'id': uuid.uuid4().hex}))['proposal']['accepted']
        observe(state['stones']); assert state['ready']
        passed('Capture removal, duplicate suppression and stale rejection')
        call('release'); call('acquire'); stream = uuid.uuid4().hex; sequence = 0
        observe(state['stones']); assert state['ready'] and state['depth'] == 3
        assert call('library')['games']; call('release'); assert not state['active']
        passed('Reconnect and saved-history preservation')
        if os.environ.get('SMARTGO_ANDROID_PROOF') == '1':
            import subprocess
            adb = ['/opt/android-sdk/platform-tools/adb', '-s', 'emulator-5580']
            assert subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() == '1'
            config = dict(host='10.0.2.2', port=18767, **pairing)
            fixture_file = runtime / 'desktop-proof.json'
            fixture_file.write_text(json.dumps(config)); fixture_file.chmod(0o600)
            subprocess.run(adb + ['push', str(fixture_file), '/data/local/tmp/smartgo-proof.json'], check=True, capture_output=True)
            subprocess.run(adb + ['shell', 'run-as', 'cn.izis.local.smartgo.dev', 'cp', '/data/local/tmp/smartgo-proof.json', 'files/desktop-proof.json'], check=True, capture_output=True)
            try:
                result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'desktopProof', 'true', '-e', 'class', 'cn.izis.izisbridge.DesktopDeviceTest,cn.izis.izisbridge.ServiceGameplayTest', 'cn.izis.local.smartgo.dev.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=600)
                (runtime / 'android-desktop-proof.log').write_text(result.stdout + result.stderr)
                assert 'OK (5 tests)' in result.stdout, 'Android desktop proof failed; see log'
                passed('API25 production DesktopConnection: pinned TLS, proposal, real AI and save')
            finally:
                subprocess.run(adb + ['shell', 'run-as', 'cn.izis.local.smartgo.dev', 'rm', 'files/desktop-proof.json'], capture_output=True)
                subprocess.run(adb + ['shell', 'rm', '/data/local/tmp/smartgo-proof.json'], capture_output=True)
                fixture_file.unlink()
    except Exception as exc:
        import traceback
        traceback.print_exc(); errors.append(type(exc).__name__ + ': ' + str(exc))
    finally:
        path = runtime / 'service-virtual-proof.json'
        path.write_text(json.dumps(dict(passed=not errors, checks=checks, errors=errors), indent=2))
        Clock.schedule_once(lambda dt: app.stop(), 0)
Clock.schedule_once(lambda dt: threading.Thread(target=exercise, daemon=True).start(), 3)
app.run()
raise SystemExit(bool(errors))
