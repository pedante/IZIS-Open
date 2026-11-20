import json
from types import SimpleNamespace
import uuid

import pytest
from katrain.core.game import Move
from izis_katrain.control import ControlError, DesktopSession, atomic_json
from test_sync import system


def session(system, tmp_path):
    app, link, sync = system
    app.izis, app.board_link, app.engine = sync, link, app.game.engines["B"]
    app.engine.check_alive = lambda: True
    app._do_undo = lambda n: app.game.undo(1)
    return app, link, sync, DesktopSession(app, tmp_path)


def request(control, op, client="phone", **extra):
    return control.execute(dict(client=client, id=uuid.uuid4().hex, op=op,
                                game_id=control.game_id, revision=control.revision, **extra))


def test_lease_revision_and_idempotent_pass(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    initial = request(control, "acquire")
    assert initial["controller"] == "touchscreen"
    assert initial["analysis"] == "unverified"
    with pytest.raises(ControlError, match="Another"):
        request(control, "acquire", client="other")
    command = dict(client="phone", id="once", op="pass", revision=control.revision, game_id=control.game_id)
    response = control.execute(command)
    assert app.game.current_node.depth == 1
    assert control.execute(command) == response
    assert app.game.current_node.depth == 1
    with pytest.raises(ControlError, match="Game changed"):
        control.execute({**command, "id": "different"})
    assert len(list(control.directory.glob("*.json"))) == 1
    saved = json.loads(next(control.directory.glob("*.json")).read_text())
    assert saved["path"] == [0] and ";B[]" in saved["sgf"]


def test_correction_blocks_commands_and_saves_undo_path(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    request(control, "acquire")
    app.game.play(Move((3, 3), "B")); sync.refresh()
    request(control, "status")
    with pytest.raises(ControlError, match="correction"):
        request(control, "undo")
    link.board = {(3, 3): "B"}; sync.refresh()
    request(control, "undo")
    saved = control.capture()
    assert saved["path"] == []
    assert "B[" in saved["sgf"]  # the variation is retained
    assert not sync.ready


def test_screen_policy_does_not_change_led_hint(system, tmp_path):
    from test_training import analysis
    app, link, sync, control = session(system, tmp_path)
    analysis(app.game.current_node)
    app.engine.last_analysis_response = 1234.0
    sync.hint = "five"; sync.refresh()
    assert len(link.points) == 5
    state = request(control, "status")
    assert len(state["policy"]) == 362 and len(link.points) == 5
    assert state["last_analysis"] is not None


def test_resume_rejects_paths_and_bad_options(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    request(control, "acquire")
    with pytest.raises(ControlError, match="Invalid saved"):
        request(control, "resume", saved_id="../../secret")
    with pytest.raises(ControlError, match="Unsupported board"):
        request(control, "new", size=12)
    with pytest.raises(ControlError, match="Thresholds"):
        request(control, "thresholds", values=[float("nan")] * 6)


def test_atomic_json_preserves_previous_on_serialization_failure(tmp_path):
    path = tmp_path / "game.json"
    atomic_json(path, {"moves": [1]})
    with pytest.raises(ValueError):
        atomic_json(path, {"moves": float("nan")})
    assert json.loads(path.read_text()) == {"moves": [1]}
    assert path.stat().st_mode & 0o777 == 0o600


def test_review_never_overwrites_saved_cursor_or_result(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    app.game.play(Move((3, 3), "B"))
    app.game.current_node.end_state = "B+R"
    saved = control.capture()
    control.review = True
    app.game.undo()
    assert control.capture() == saved
    assert saved["result"] == "B+R" and saved["path"] == [0]


def test_tls_responses_explicitly_close_connections(tmp_path):
    import http.client
    import ssl
    from izis_katrain.control import ControlServer

    def app(message, request, future, deadline):
        future.set_result({"ok": True})

    server = ControlServer(app, tmp_path / "pairing", host="127.0.0.1", port=0)
    try:
        context = ssl._create_unverified_context()
        for token, expected in [(server.pairing["token"], 200), ("invalid", 401)]:
            connection = http.client.HTTPSConnection("127.0.0.1", server.http.server_port, context=context)
            try:
                connection.request("POST", "/v1/control", json.dumps({"op": "status"}),
                                   {"Authorization": "Bearer " + token})
                response = connection.getresponse()
                assert response.status == expected
                assert response.getheader("Connection") == "close"
                assert len(response.read()) == int(response.getheader("Content-Length"))
            finally:
                connection.close()
    finally:
        server.close()


def test_fast_profile_restores_full_and_rejects_remote(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    original = dict(backend="local", max_visits=500, fast_visits=100, max_time=30)
    app.engine.config = original.copy()
    control.set_engine_profile("fast")
    assert app.engine.config == dict(backend="local", max_visits=64, fast_visits=32, max_time=1.0)
    control.set_engine_profile("full")
    assert app.engine.config == original
    app.engine.config["backend"] = "remote"
    remote = app.engine.config.copy()
    with pytest.raises(ControlError, match="local"):
        control.set_engine_profile("fast")
    control.set_engine_profile("full")
    assert app.engine.config == remote
