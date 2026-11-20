import json
import stat
import sys

import pytest

from izis_katrain.launch import configure
from izis_katrain.remote import redact, validate_read19_url
import katrain.core.constants as constants


URL = "wss://read19.com/v1/katago/kg_live_TEST_ONLY"


@pytest.fixture(autouse=True)
def isolate_profile_globals(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["profile-test"])
    monkeypatch.setattr(constants, "DATA_FOLDER", constants.DATA_FOLDER)
    for name in ("KIVY_HOME", "KIVY_NO_ARGS", "KIVY_NO_CONSOLELOG"):
        monkeypatch.setenv(name, "profile-test")


def test_remote_profile_is_private_separate_and_preserved(tmp_path):
    local = configure(tmp_path / "local", "unused", 9, 40)
    original = (local / "config.json").read_bytes()
    remote = configure(tmp_path / "remote", "missing-engine", 9, 40, "remote", URL)
    config = remote / "config.json"
    assert json.loads(config.read_text())["engine"]["backend"] == "remote"
    assert stat.S_IMODE(config.stat().st_mode) == 0o600
    assert stat.S_IMODE(remote.stat().st_mode) == 0o700
    saved = config.read_bytes()
    configure(remote, "unused", 9, 5, "remote")
    assert config.read_bytes() == saved
    with pytest.raises(ValueError, match="engine differs"):
        configure(local, "unused", 9, 40, "remote", URL)
    assert (local / "config.json").read_bytes() == original
    with pytest.raises(ValueError, match="engine differs"):
        configure(remote, "unused", 9, 40)


@pytest.mark.parametrize("url", ["ws://read19.com/v1/katago/token", "wss://evil.test/v1/katago/token",
    "wss://read19.com.evil.test/v1/katago/token", "wss://read19.com/v1/katago/", URL + "?extra=secret"])
def test_read19_url_validation_does_not_echo_invalid_secret(url):
    with pytest.raises(ValueError) as error:
        validate_read19_url(url)
    assert url not in str(error.value)


def test_credential_redaction_and_setup_validation():
    assert validate_read19_url(" " + URL + "\n") == URL
    config = {"engine": {"remote_url": URL}}
    for message in ["Connecting to " + URL, "Rejected token kg_live_TEST_ONLY", repr({"url": URL})]:
        clean = redact(message, config)
        assert "kg_live_TEST_ONLY" not in clean and URL not in clean
    assert "secret" not in redact("wss://some.host/path/secret")


def test_remote_requires_setup(tmp_path):
    with pytest.raises(ValueError, match="not configured"):
        configure(tmp_path, "unused", 9, 40, "remote")
    assert not (tmp_path / "config.json").exists()


def test_read19_launch_does_not_require_local_engine(tmp_path, monkeypatch):
    from izis_katrain import launch
    configure(tmp_path, "nonexistent", 9, 40, "remote", URL)
    monkeypatch.setattr(sys, "argv", ["izis", "--engine", "read19", "--size", "9",
                                    "--runtime", str(tmp_path), "--katago", "/no/local/katago"])
    ran = []
    class App:
        def run(self): ran.append(True)
    monkeypatch.setattr(launch, "create_app", lambda *args: App())
    launch.main()
    assert ran == [True]


def test_setup_refuses_echo_fallback(tmp_path, monkeypatch, capsys):
    import getpass
    import warnings
    from izis_katrain import launch
    monkeypatch.setattr(sys, "argv", ["izis", "--engine", "read19", "--setup-read19",
                                    "--runtime", str(tmp_path)])
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True)
    def no_hidden_input(*args):
        warnings.warn("Cannot hide input", getpass.GetPassWarning)
        pytest.fail("Must never fall back to echoed input")
    monkeypatch.setattr(getpass, "getpass", no_hidden_input)
    with pytest.raises(SystemExit): launch.main()
    assert "Hidden input is unavailable" in capsys.readouterr().err
    assert not (tmp_path / "config.json").exists()


def test_read19_busy_retry_is_bounded_and_discarded_after_reset(monkeypatch):
    from katrain.core.remote_engine import RemoteKataGoEngine
    from izis_katrain import remote
    sent, timers, errors = [], [], []
    def init(self, *args):
        self.query_generation = 0
        self._closing = False
        self.katrain = type('App', (), {'log': lambda *args: None})()
    monkeypatch.setattr(RemoteKataGoEngine, '__init__', init)
    monkeypatch.setattr(RemoteKataGoEngine, 'send_query', lambda self, q, cb, err, *args: sent.append((q,err)))
    class Timer:
        def __init__(self, delay, fn, args): timers.append((delay,fn,args))
        def start(self): pass
    monkeypatch.setattr(remote.threading, 'Timer', Timer)
    engine = remote.make_engine(None, {'backend':'remote','remote_url':URL})
    engine.send_query({'id':'old','moves':[]}, None, errors.append)
    busy = {'errorCode':'CONCURRENCY_LIMIT_EXCEEDED'}
    for i in range(5):
        sent[-1][1](busy)
        delay, fn, args = timers[-1]
        assert delay == 2 ** (i+1)
        fn(*args)
        assert 'id' not in sent[-1][0]
    sent[-1][1](busy)
    assert errors == [busy] and len(timers) == 5
    engine.send_query({}, None, errors.append)
    sent[-1][1](busy)
    _, fn, args = timers[-1]
    before = len(sent)
    engine.query_generation += 1
    fn(*args)
    assert len(sent) == before
    engine.send_query({}, None, errors.append)
    sent[-1][1](busy)
    _, fn, args = timers[-1]
    before = len(sent)
    engine._closing = True
    fn(*args)
    assert len(sent) == before
