"""Run the actual KaTrain GUI with a small, opt-in board extension."""

import argparse
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def configure(runtime, katago, size, visits, backend="local", remote_url=None):
    if backend not in ("local", "remote"):
        raise ValueError("Unsupported engine backend")
    runtime = Path(runtime).resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("KIVY_HOME", str(runtime / "kivy"))
    os.environ.setdefault("KIVY_NO_ARGS", "1")
    os.environ.setdefault("KIVY_NO_CONSOLELOG", "1")
    # Keep KaTrain's settings and engine cache out of the user's normal profile.
    import katrain.core.constants as constants
    constants.DATA_FOLDER = str(runtime)
    config = json.loads((ROOT / "vendor/katrain/katrain/config.json").read_text())
    engine_cfg = runtime / "analysis.cfg"
    if not engine_cfg.exists():
        engine_cfg.write_text("numAnalysisThreads = 1\nnumSearchThreads = 2\nnnMaxBatchSize = 4\nnnCacheSizePowerOfTwo = 18\n")
    config["engine"].update(
        backend=backend, katago=str(Path(katago).resolve()),
        config=str(engine_cfg), max_visits=visits, fast_visits=visits,
        max_time=30, allow_recovery=False,
    )
    config["game"].update(size=size, handicap=0)
    path = runtime / "config.json"
    exists = path.exists()
    if exists:
        config = json.loads(path.read_text())
        if config["game"]["size"] != size:
            raise ValueError("Saved board size differs; use a new --runtime directory or matching --size")
        if config["engine"].get("backend", "local") != backend:
            raise ValueError("Saved engine differs; select the matching --engine or use a separate --runtime")
    if backend == "remote":
        if remote_url is not None:
            config["engine"]["remote_url"] = remote_url
        if not config["engine"].get("remote_url"):
            raise ValueError("Read19 is not configured; run with --engine read19 --setup-read19 first")
        runtime.chmod(0o700)
    if not exists or remote_url is not None:
        # Create private before writing: the remote URL contains an access token.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(config, stream, indent=2)
    if backend == "remote":
        path.chmod(0o600)
    sys.argv = [sys.argv[0], str(path)]
    return runtime


def create_app(host, port, size, runtime, human_only=False, control_port=0):
    import katrain.__main__ as main
    from .remote import make_engine
    def observed_engine(katrain, config):
        import time
        engine = make_engine(katrain, config)
        request_analysis = engine.request_analysis
        engine.last_analysis_response = None
        def request(node, callback, *args, **kwargs):
            def received(result, partial):
                if not partial and result.get("rootInfo", {}).get("visits", 0) > 0:
                    engine.last_analysis_response = time.time()
                callback(result, partial)
            return request_analysis(node, received, *args, **kwargs)
        engine.request_analysis = request
        return engine
    main.make_engine = observed_engine if control_port else make_engine
    from kivy.clock import Clock
    from katrain.core.constants import AI_DEFAULT, PLAYER_AI
    from .sync import Synchronizer
    from .physical import PhysicalBoard

    class IzisGui(main.KaTrainGui):
        izis = None
        touch = None
        control_server = None

        def __call__(self, message, *args, **kwargs):
            if self.touch and self.touch.leased and message.replace("-", "_") not in (
                "touch_request", "izis_tick", "update_state", "ai_move", "save_game"
            ):
                self.log("Touchscreen owns this game; disconnect it before using desktop controls")
                return
            super().__call__(message, *args, **kwargs)

        def _do_touch_request(self, request, future, deadline):
            import time
            if future.cancelled() or time.monotonic() > deadline:
                return
            if not future.set_running_or_notify_cancel():
                return
            try:
                future.set_result(self.touch.execute(request))
            except Exception as exc:
                future.set_exception(exc)

        def log(self, message, *args, **kwargs):
            from .remote import redact
            super().log(redact(message, getattr(self, "_config", {})), *args, **kwargs)

        def start(self):
            super().start()
            self.board_link = PhysicalBoard(size)
            self.izis = Synchronizer(self, self.board_link, debounce=0.6)
            from kivy.uix.button import Button
            self.connect_button = Button(text="Connect Izis", size_hint=(1, None), height=40)
            self.connect_button.bind(on_release=lambda *_: self("izis_connection"))
            self.board_controls.parent.add_widget(self.connect_button)
            if not human_only:
                self.update_player("W", player_type=PLAYER_AI, player_subtype=AI_DEFAULT)
            self.izis_clock = Clock.schedule_interval(lambda dt: self("izis_tick"), 0.1)
            if control_port:
                from .control import ControlServer, DesktopSession
                self.touch = DesktopSession(self, runtime)
                self.control_server = ControlServer(self, runtime / "pairing", port=control_port)
                pairing_button = Button(text="Android pairing", size_hint=(1, None), height=40)
                def pairing(*_):
                    from kivy.uix.popup import Popup
                    from kivy.uix.textinput import TextInput
                    code = self.control_server.pairing
                    Popup(title="Private pairing — enter on your Izis screen",
                          content=TextInput(text=f"Port: {control_port}\nSHA-256: {code['fingerprint']}\nPairing key: {code['token']}",
                                            readonly=True), size_hint=(0.9, 0.5)).open()
                pairing_button.bind(on_release=pairing)
                self.board_controls.parent.add_widget(pairing_button)

        def _do_izis_connection(self):
            if self.board_link.active:
                self.board_link.close()
            else:
                self.board_link.start()
            Clock.schedule_once(lambda dt: setattr(self.connect_button, "text",
                                "Disconnect Izis" if self.board_link.active else "Connect Izis"), 0)
            self.izis.refresh()

        def _do_izis_tick(self):
            if self.touch:
                self.disabled = self.touch.leased
                if self.touch.save_error:
                    try:
                        self.touch.capture()
                    except Exception:
                        self.izis.ready = False
                        self.izis.status = "Autosave failed; check desktop storage"
                        return
            if self.game.board_size != (self.board_link.size, self.board_link.size):
                self.izis.ready = False
                self.izis.status = f"Restart the adapter for board size {self.game.board_size}"
                self.board_link.display({})
                return
            self.izis.refresh(accept_move=False)
            for event in self.board_link.buttons():
                self.izis.button(*event)
            if self.touch:
                try:
                    self.touch.capture()
                except Exception:
                    self.izis.ready = False
                    self.izis.status = "Autosave failed; check desktop storage"

        def _do_update_state(self, *args, **kwargs):
            if self.izis and self.game.board_size == (self.board_link.size, self.board_link.size):
                self.izis.refresh()
            super()._do_update_state(*args, **kwargs)
            if self.izis:
                if self.game.board_size == (self.board_link.size, self.board_link.size):
                    self.izis.refresh()
                state = json.dumps(self.izis.state(), indent=2)
                temporary = runtime / "state.tmp"
                temporary.write_text(state)
                temporary.replace(runtime / "state.json")

        def _do_play(self, coords):
            if self.touch and self.touch.save_error:
                return
            if self.izis and self.board_link.active:
                self.izis.refresh()
                if not self.izis.ready:
                    return
            super()._do_play(coords)

        def _do_ai_move(self, node=None):
            if self.touch and (self.touch.save_error or self.touch.review):
                return
            if self.izis and self.board_link.active:
                if self.game.board_size != (self.board_link.size, self.board_link.size):
                    return
                self.izis.refresh()
                if not self.izis.ready:
                    return
            super()._do_ai_move(node)

    # KaTrainApp.build resolves this class at runtime; upstream files stay intact.
    main.KaTrainGui = IzisGui

    class IzisApp(main.KaTrainApp):
        def on_stop(self):
            if getattr(self.gui, "control_server", None):
                self.gui.control_server.close()
            if getattr(self.gui, "izis", None):
                self.gui.izis_clock.cancel()
                self.gui.board_link.close()
            if self.gui.engine:
                self.gui.engine.shutdown(finish=False)

    return IzisApp()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--size", type=int, choices=(9, 13, 19), default=19)
    parser.add_argument("--visits", type=int, default=40)
    parser.add_argument("--engine", choices=("local", "read19"), default="local")
    parser.add_argument("--setup-read19", action="store_true", help="Privately enter the Read19 URL and exit without connecting")
    parser.add_argument("--katago", default=str(ROOT / "runtime/katago/squashfs-root/AppRun"))
    parser.add_argument("--runtime", help="Separate saved settings; defaults to runtime/session or runtime/read19")
    parser.add_argument("--human-only", action="store_true")
    parser.add_argument("--control-port", type=int, default=0,
                        help="Enable paired TLS Android controls (e.g. 8767); disabled by default")
    args = parser.parse_args()
    if args.visits < 2:
        parser.error("Use visits >= 2")
    if args.control_port and not 1024 <= args.control_port <= 65535:
        parser.error("Control port must be 1024–65535")
    if args.setup_read19 and args.engine != "read19":
        parser.error("--setup-read19 requires --engine read19")
    if args.engine == "local" and not Path(args.katago).is_file():
        parser.error("Local mode requires an existing KataGo executable")
    url = None
    if args.setup_read19:
        import getpass
        import warnings
        from .remote import validate_read19_url
        if not sys.stdin.isatty():
            parser.error("Run setup in your terminal so the URL can be entered without echo")
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                url = validate_read19_url(getpass.getpass("Read19 KaTrain WSS URL (hidden): "))
        except (getpass.GetPassWarning, EOFError):
            parser.error("Hidden input is unavailable; run setup in a private interactive terminal")
        except ValueError as exc:
            parser.error(str(exc))
    profile = args.runtime or str(ROOT / "runtime" / ("session" if args.engine == "local" else "read19"))
    try:
        runtime = configure(profile, args.katago, args.size, args.visits,
                            backend="local" if args.engine == "local" else "remote", remote_url=url)
        if args.engine == "read19":
            from .remote import validate_read19_url
            validate_read19_url(json.loads((runtime / "config.json").read_text())["engine"]["remote_url"])
    except ValueError as exc:
        parser.error(str(exc))
    if args.setup_read19:
        print("Read19 profile saved privately. No cloud connection was made.")
        return
    app = create_app(args.host, args.port, args.size, runtime, args.human_only, args.control_port)
    app.run()


if __name__ == "__main__":
    main()
