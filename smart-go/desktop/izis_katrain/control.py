"""Opt-in TLS touchscreen API; all game work runs in KaTrain's message queue."""

from collections import OrderedDict
from concurrent.futures import Future, TimeoutError
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import threading
import time
import uuid


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(".tmp")
    with open(temporary, "w", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def prepare_pairing(directory):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    cert, key, config = (directory / name for name in ("cert.pem", "key.pem", "pairing.json"))
    if not config.exists():
        # Never replace an existing identity silently after partial setup.
        if not cert.exists() or not key.exists():
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes",
                            "-keyout", str(key), "-out", str(cert), "-days", "3650",
                            "-subj", "/CN=Izis Desktop"], check=True, capture_output=True)
            key.chmod(0o600)
        der = ssl.PEM_cert_to_DER_cert(cert.read_text())
        atomic_json(config, {"token": secrets.token_urlsafe(32),
                             "fingerprint": hashlib.sha256(der).hexdigest()})
    return json.loads(config.read_text())


class ControlError(Exception):
    pass


class DesktopSession:
    """One controller lease, revision checks, idempotent commands and durable SGFs."""

    def __init__(self, app, directory):
        self.app = app
        self.directory = Path(directory) / "games"
        self.directory.mkdir(parents=True, exist_ok=True)
        self.epoch = uuid.uuid4().hex
        self.owner = None
        self.expires = 0
        self.cache = OrderedDict()
        self.game = None
        self.game_id = None
        self.revision = 0
        self.signature = None
        self.last_analysis = None
        self.review = False
        self.save_error = False
        self.test_status = "Not requested"
        self.test_started = 0

    @property
    def leased(self):
        return self.owner is not None and time.monotonic() < self.expires

    def capture(self):
        game = self.app.game
        if game is not self.game:
            self.game, self.game_id = game, uuid.uuid4().hex
            self.signature = None
        node = game.current_node
        path = []
        cursor = node
        while cursor.parent:
            path.append(cursor.parent.children.index(cursor))
            cursor = cursor.parent
        path.reverse()
        signature = (id(game), tuple(path), game.end_result)
        self.last_analysis = getattr(self.app.engine, "last_analysis_response", None)
        if signature != self.signature:
            if not self.review:
                game.update_root_properties()
                if not game.end_result:
                    game.root.properties.pop("RE", None)
            record = {
                "id": self.game_id, "sgf": game.root.sgf(), "path": path,
                "result": game.end_result, "size": game.board_size[0], "updated": time.time(),
                "players": {c: {"player_type": p.player_type, "player_subtype": p.player_subtype}
                            for c, p in self.app.players_info.items()},
            }
            try:
                if not self.review:
                    atomic_json(self.directory / f"{self.game_id}.json", record)
            except OSError:
                self.save_error = True
                raise ControlError("Autosave failed; free desktop storage before continuing") from None
            self.save_error = False
            self.signature = signature
            self.revision += 1
        return json.loads((self.directory / f"{self.game_id}.json").read_text())

    def state(self):
        record = self.capture()
        self.app.izis.refresh()
        state = self.app.izis.state()
        node = self.app.game.current_node
        engine = self.app.engine
        alive = bool(engine and getattr(engine, "check_alive", lambda: False)())
        if self.test_status == "Analyzing" and time.monotonic() - self.test_started > 90:
            self.test_status = "Timed out; retry explicitly"
        state.update(
            protocol=1, controller_epoch=self.epoch, game_id=self.game_id, revision=self.revision, record=record,
            computer=True, katrain=True, engine=alive,
            analysis="ready" if alive and self.last_analysis is not None else "unverified",
            current_analysis_complete=node.analysis_complete,
            last_analysis=self.last_analysis, result=self.app.game.end_result,
            test_analysis=self.test_status,
            policy=node.policy, candidate_details=node.candidate_moves[:10],
            physical=[[x, y, c] for (x, y), c in (self.app.izis.observed or {}).items()],
            physical_available=self.app.izis.observed is not None,
            observation_stable=(hasattr(self.app.board_link, "changed") and self.app.board_link.active
                and time.monotonic() - self.app.board_link.changed >= 0.6
                and time.monotonic() - self.app.board_link.received <= 30),
            controller="touchscreen" if self.leased else "desktop", review=self.review,
            capabilities={"teaching": True, "sizes": [9, 13, 19],
                          "difficulty": ["ai:default", "ai:rank"], "thresholds": True},
            thresholds=self.app.config("trainer/eval_thresholds"),
        )
        return state

    def execute(self, request):
        client, request_id, op = (request.get(k) for k in ("client", "id", "op"))
        if not all(isinstance(v, str) and 1 <= len(v) <= 80 for v in (client, request_id, op)):
            raise ControlError("Invalid command envelope")
        key = (client, request_id)
        if key in self.cache:
            original, result = self.cache[key]
            if original != request:
                raise ControlError("Request ID reused with different content")
            return result
        self.capture()
        if op == "acquire":
            if self.leased and client != self.owner:
                raise ControlError("Another touchscreen controls this session")
            self.owner, self.expires = client, time.monotonic() + 20
            self.app.disabled = True
        elif op not in ("status", "library"):
            if not self.leased or self.owner != client:
                raise ControlError("Acquire control before changing this game")
            self.expires = time.monotonic() + 20
        elif self.leased and self.owner == client:
            self.expires = time.monotonic() + 20
        if op in ("status", "acquire"):
            return self.state()
        if op == "library":
            return {"games": [json.loads(p.read_text()) for p in sorted(self.directory.glob("*.json"))]}
        if op == "observe":
            if request.get("controller_epoch") != self.epoch:
                raise ControlError("Controller changed; reconnect")
            try:
                self.app.board_link.observe(request.get("physical"), request.get("stream"), request.get("sequence"))
            except ValueError as exc:
                raise ControlError(str(exc)) from None
            self.app.izis.refresh()
            return self.state()
        if op == "physical_proposal":
            result = self.physical_proposal(request)
            self.cache[key] = (json.loads(json.dumps(request)), result)
            while len(self.cache) > 256:
                self.cache.popitem(last=False)
            return result
        if op == "release":
            self.app.board_link.close()
            self.owner = None
            self.app.disabled = False
        elif op == "connect_board":
            self.app.board_link.start()
        elif op == "suggest":
            if self.review or self.app.game.end_result or not self.app.izis.ready:
                raise ControlError("Suggestions require a synchronized active game")
            node = self.app.game.current_node
            if not node.analysis_complete:
                node.analyze(self.app.engine)
        elif op == "test_analysis":
            if self.test_status == "Analyzing":
                raise ControlError("Test analysis already running")
            self.test_status, self.test_started = "Analyzing", time.monotonic()
            def completed(result, partial):
                if not partial and result.get("rootInfo", {}).get("visits", 0) > 0:
                    self.last_analysis = time.time()
                    self.test_status = "Successful analysis response"
            def rejected(_result):
                self.test_status = "Analysis rejected by backend; inspect engine status"
            self.app.engine.request_analysis(self.app.game.current_node, callback=completed,
                                             error_callback=rejected, visits=2)
        else:
            if request.get("revision") != self.revision or request.get("game_id") != self.game_id:
                raise ControlError("Game changed; refresh before retrying")
            self.app.izis.refresh()
            if op == "new":
                link = self.app.board_link
                if getattr(link, "active", True):
                    physical, received, _, _ = link.read()
                    if physical is None or time.monotonic() - received > 30:
                        raise ControlError("Wait for fresh board sensors before starting a new game")
                    if physical:
                        raise ControlError("Clear the board to start a new game")
            elif op not in ("resign", "finish") and not self.app.izis.ready:
                raise ControlError("Resolve physical-board correction before changing the game")
            self.apply(op, request)
        self.app.izis.refresh()
        result = self.state()
        self.cache[key] = (json.loads(json.dumps(request)), result)
        while len(self.cache) > 256:
            self.cache.popitem(last=False)
        return result

    def physical_proposal(self, request):
        """Explicit confirmation on the existing authenticated KaTrain queue."""
        from katrain.core.game import IllegalMoveException, Move

        def rejected(reason):
            return dict(self.state(), proposal={"id": request["id"], "accepted": False, "reason": reason})

        if (request.get("controller_epoch") != self.epoch or
                request.get("revision") != self.revision or request.get("game_id") != self.game_id):
            return rejected("Stale controller or game; reconcile before proposing")
        game = self.app.game
        node = game.current_node
        if self.review or game.end_result or game.insert_mode or not self.app.next_player_info.human:
            return rejected("No active human turn")
        point, color, grid = (request.get(k) for k in ("point", "color", "physical"))
        size = game.board_size[0]
        if (not isinstance(point, list) or len(point) != 2 or
                any(type(v) is not int or not 0 <= v < size for v in point) or color != node.next_player):
            return rejected("Invalid move or color")
        if (not isinstance(grid, list) or len(grid) != 361 or
                any(type(v) is not int or v not in (0, 1, 2) for v in grid)):
            return rejected("A complete 361-position snapshot is required")
        offset = (19 - size) // 2
        physical = {(i % 19 - offset, i // 19 - offset): "BW"[v - 1]
                    for i, v in enumerate(grid) if v}
        target = {s.coords: s.player for s in game.stones}
        additions = {p: c for p, c in physical.items() if target.get(p) != c}
        removed = {p for p, c in target.items() if physical.get(p) != c}
        if additions != {tuple(point): color} or tuple(point) in target:
            return rejected("Correct extra, replaced or wrong-color stones")
        link = self.app.board_link
        if hasattr(link, "observe") and (not link.active or link.board != physical or
                time.monotonic() - link.received > 30 or time.monotonic() - link.changed < 0.6):
            return rejected("Wait for a fresh stable observation")
        move = Move(tuple(point), player=color)
        try:
            with game._lock:
                try:
                    game._validate_move_and_update_chains(move, False)
                    if not removed <= {s.coords for s in game.last_capture}:
                        raise IllegalMoveException("Restore unrelated removed stones")
                finally:
                    game._calculate_groups()
            game.play(move, expected_node=node)
        except IllegalMoveException as exc:
            return rejected(str(exc))
        if game.current_node is node:
            return rejected("Game changed before acceptance")
        self.app.izis.refresh()
        return dict(self.state(), proposal={"id": request["id"], "accepted": True})

    def set_engine_profile(self, profile):
        if profile not in ("full", "fast"):
            raise ControlError("Unsupported engine profile")
        config = self.app.engine.config
        if config.get("backend", "local") != "local":
            if profile == "fast":
                raise ControlError("Fast Testing requires local KataGo")
            return
        if not hasattr(self, "full_engine_limits"):
            self.full_engine_limits = {k: config[k] for k in ("max_visits", "fast_visits", "max_time")}
        config.update(dict(max_visits=64, fast_visits=32, max_time=1.0) if profile == "fast" else self.full_engine_limits)

    def apply(self, op, request):
        from katrain.core.constants import AI_DEFAULT, PLAYER_AI, PLAYER_HUMAN, PLAYING_NORMAL, PLAYING_TEACHING
        from katrain.core.game import KaTrainSGF, Move

        app, game = self.app, self.app.game
        if op in ("new", "resume", "review"):
            size = request.get("size", game.board_size[0])
            record = None
            if op != "new":
                identifier = request.get("saved_id", "")
                if not isinstance(identifier, str) or len(identifier) != 32 or any(c not in "0123456789abcdef" for c in identifier):
                    raise ControlError("Invalid saved game")
                path = self.directory / f"{identifier}.json"
                if not path.is_file():
                    raise ControlError("Saved game not found")
                record = json.loads(path.read_text())
                size = record["size"]
                if op == "resume" and record["result"]:
                    raise ControlError("Finished games can be reviewed, not resumed")
            if size not in (9, 13, 19):
                raise ControlError("Unsupported board size")
            color = request.get("color", "B")
            difficulty = request.get("difficulty", AI_DEFAULT)
            mode = request.get("mode", "normal")
            if color not in ("B", "W", "random") or difficulty not in (AI_DEFAULT, "ai:rank") or mode not in ("normal", "teaching"):
                raise ControlError("Unsupported game options")
            if color == "random":
                color = secrets.choice("BW")
            self.set_engine_profile(request.get("engine_profile", "full"))
            app.board_link.close()
            app.board_link.size = size
            app._config["game"].update(size=size, handicap=0)
            from katrain.core.constants import MODE_ANALYZE, MODE_PLAY
            app.play_mode.mode = MODE_ANALYZE if record else MODE_PLAY
            app._do_new_game(move_tree=KaTrainSGF.parse_sgf(record["sgf"]) if record else None)
            self.review = op == "review"
            if record:
                node = app.game.root
                for index in record["path"]:
                    node = node.children[index]
                app.game.set_current_node(node)
                node.end_state = record["result"]
                for c, settings in record["players"].items():
                    app.update_player(c, **settings)
                self.game, self.game_id = app.game, record["id"]
                self.signature = None
            else:
                for c in "BW":
                    app.update_player(c, player_type=PLAYER_HUMAN if c == color else PLAYER_AI,
                                      player_subtype=(PLAYING_TEACHING if mode == "teaching" else PLAYING_NORMAL) if c == color else difficulty)
            # Direct assignment avoids an asynchronous mode toggle racing the next command.
            app.play_mode.mode = MODE_ANALYZE if self.review else MODE_PLAY
            app.board_link.start()
        elif op == "pass":
            if self.review or game.end_result or not app.next_player_info.human:
                raise ControlError("Pass is only available on your turn in an unfinished game")
            game.play(Move(None, player=game.current_node.next_player))
        elif op == "undo" and not self.review and not game.end_result:
            app._do_undo("smart")
        elif op == "resign":
            humans = [c for c, p in app.players_info.items() if p.human]
            if len(humans) != 1 or self.review or game.end_result:
                raise ControlError("No active human-versus-engine game")
            game.current_node.end_state = ("W" if humans[0] == "B" else "B") + "+R"
        elif op == "score":
            if not game.current_node.analysis_complete:
                raise ControlError("Analysis has not finished; scoring unavailable")
            # An estimate is shown separately; never silently record it as an agreed result.
        elif op == "finish" and not self.review:
            result = request.get("result", "")
            import re
            if not isinstance(result, str) or not re.fullmatch(r"(?:[BW]\+(?:R|[0-9]+(?:\.[0-9]+)?)|0|Void)", result):
                raise ControlError("Enter an agreed SGF result, e.g. B+3.5, W+R or 0")
            game.current_node.end_state = result
        elif op in ("previous", "next") and self.review:
            (game.undo if op == "previous" else game.redo)(1)
        elif op == "thresholds":
            values = request.get("values")
            if (not isinstance(values, list) or len(values) != len(app.config("trainer/eval_thresholds"))
                    or any(type(v) not in (int, float) or not 0 <= v <= 100 for v in values)
                    or values != sorted(values, reverse=True)):
                raise ControlError("Thresholds must be descending finite numbers between 0 and 100")
            app._config["trainer"]["eval_thresholds"] = values
            app.save_config("trainer")
        else:
            raise ControlError("Unsupported command")


class ControlServer:
    def __init__(self, app, directory, host="0.0.0.0", port=8767):
        self.app = app
        self.pairing = prepare_pairing(directory)
        self.stopped = threading.Event()
        self.slots = threading.BoundedSemaphore(8)
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                if not owner.slots.acquire(blocking=False):
                    self.send_error(503)
                    return
                try:
                    self.connection.settimeout(5)
                    if self.path != "/v1/control":
                        self.respond(404, {"error": "Unsupported endpoint"})
                        return
                    auth = self.headers.get("Authorization", "")
                    if not secrets.compare_digest(auth.encode(), ("Bearer " + owner.pairing["token"]).encode()):
                        self.respond(401, {"error": "Authentication rejected; pair again"})
                        return
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 16384:
                        self.respond(400, {"error": "Invalid request size"})
                        return
                    request = json.loads(self.rfile.read(length))
                    if not isinstance(request, dict):
                        raise ValueError()
                    future = Future()
                    owner.app("touch_request", request, future, time.monotonic() + 8)
                    try:
                        result = future.result(timeout=9)
                    except TimeoutError:
                        future.cancel()
                        self.respond(503, {"error": "KaTrain is busy; refresh state before retrying"})
                        return
                    self.respond(200, result)
                except ControlError as exc:
                    self.respond(409, {"error": str(exc)})
                except (ValueError, UnicodeError):
                    self.respond(400, {"error": "Invalid request"})
                except (OSError, TimeoutError):
                    pass
                except Exception:
                    self.respond(503, {"error": "KaTrain unavailable"})
                finally:
                    owner.slots.release()

            def respond(self, code, value):
                body = json.dumps(value, allow_nan=False).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(Path(directory) / "cert.pem"), str(Path(directory) / "key.pem"))
        class TLSServer(ThreadingHTTPServer):
            daemon_threads = True
            def get_request(self):
                connection, address = super().get_request()
                connection.settimeout(5)
                try:
                    return context.wrap_socket(connection, server_side=True), address
                except Exception:
                    connection.close()
                    raise
        self.http = TLSServer((host, port), Handler)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.discovery = threading.Thread(target=self.discover, args=(port,), daemon=True)
        self.discovery.start()

    def discover(self, port):
        # Discovery is advisory only; the touchscreen still verifies the out-of-band TLS pin.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            try:
                sock.bind(("", port))
                sock.settimeout(0.5)
                while not self.stopped.is_set():
                    try:
                        data, address = sock.recvfrom(128)
                    except socket.timeout:
                        continue
                    if data.startswith(b"IZIS_DISCOVER_1 ") and len(data) <= 80:
                        sock.sendto(data + b" " + str(port).encode(), address)
            except OSError:
                return

    def close(self):
        self.stopped.set()
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=2)
        self.discovery.join(timeout=2)
