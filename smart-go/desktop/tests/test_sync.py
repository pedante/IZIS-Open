import time

import pytest

from katrain.core.base_katrain import KaTrainBase
from katrain.core.game import Game, Move
from izis_katrain.protocol import Frames, coords, decode, index, matrix
from izis_katrain.sync import Synchronizer


class NoEngine:
    def request_analysis(self, *args, **kwargs):
        pass


class MemoryLink:
    def __init__(self):
        self.board = {}
        self.generation = 1
        self.points = {}
        self.received = None

    def read(self):
        return self.board, self.received or time.monotonic(), self.generation, ""

    def display(self, points):
        self.points = points


@pytest.fixture
def system():
    app = KaTrainBase(force_package_config=True)
    app.game = Game(app, NoEngine())
    link = MemoryLink()
    sync = Synchronizer(app, link, debounce=0)
    sync.refresh()
    return app, link, sync


@pytest.mark.parametrize("size", [9, 13, 19])
def test_coordinate_roundtrip_and_snapshot(size):
    for i in range(size * size):
        assert index(coords(i, size), size) == i
    assert coords(0, size) == (size - 1, size - 1)
    assert coords(size * size - 1, size) == (0, 0)
    assert decode("~SDA1" + "0" * (size * size - 2) + "2#", size) == {
        (size - 1, size - 1): "B", (0, 0): "W"}
    data = matrix({(0, 0): 2}, size)[4:-1]
    offset = (19 - size) // 2
    assert data[(offset + size - 1) * 19 + offset + size - 1] == "2"
    assert len(data) == 361 and set(data) <= set("012")


def test_invalid_and_fragmented_data():
    frames = Frames()
    assert frames.feed(b"noise~STA") == []
    assert frames.feed(b"#~broken~CTS0#") == ["~STA#", "~CTS0#"]
    with pytest.raises(ValueError):
        decode("~SDA" + "3" * 361 + "#", 19)
    with pytest.raises(ValueError):
        decode("~SDA000#", 19)
    with pytest.raises(ValueError):
        frames.feed(b"~" + b"0" * 513)


def test_human_and_ai_confirmation_exactly_once(system):
    app, link, sync = system
    link.board = {(3, 3): "B"}
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 1
    app.game.play(Move((15, 15), player="W"))
    sync.refresh()
    assert not sync.ready and link.points[(15, 15)] == 1
    link.board[(15, 15)] = "W"
    sync.refresh(accept_move=True)
    sync.refresh(accept_move=True)
    assert sync.ready and app.game.current_node.depth == 2


def test_capture_blocks_next_move_until_removed_and_undo_restores(system):
    app, link, sync = system
    for gtp, color in [("A2", "B"), ("A1", "W")]:
        app.game.play(Move.from_gtp(gtp, player=color))
    link.board = {s.coords: s.player for s in app.game.stones}
    sync.refresh()
    link.board[(1, 0)] = "B"
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 3
    assert app.game.prisoner_count["W"] == 1
    assert not sync.ready and link.points[(0, 0)] == 2
    link.board[(2, 2)] = "W"
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 3
    del link.board[(2, 2)]
    del link.board[(0, 0)]
    sync.refresh(accept_move=True)
    assert sync.ready
    app.game.undo()
    sync.refresh()
    assert not sync.ready
    assert link.points[(0, 0)] == 1 and link.points[(1, 0)] == 2
    del link.board[(1, 0)]
    link.board[(0, 0)] = "W"
    sync.refresh(accept_move=True)
    assert sync.ready and app.game.current_node.depth == 2


def test_wrong_color_multiple_stones_and_lifting_do_not_change_game(system):
    app, link, sync = system
    for board in [{(1, 1): "W"}, {(1, 1): "B", (2, 2): "W"}]:
        link.board = board
        sync.refresh(accept_move=True)
        assert app.game.current_node.depth == 0
        link.board = {}
        sync.refresh()
    link.board = {(1, 1): "B"}
    sync.refresh(accept_move=True)
    link.board = {}
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 1 and not sync.ready


def test_debounce_reconnect_and_old_analysis(system):
    app, link, sync = system
    sync.debounce = 0.35
    now = time.monotonic()
    link.received = now + 2.0
    link.board = {(1, 1): "B"}
    sync.refresh(now, accept_move=True)
    link.board = {}
    sync.refresh(now + 0.1, accept_move=True)
    sync.refresh(now + 0.5, accept_move=True)
    assert app.game.current_node.depth == 0
    link.generation += 1
    link.board = {(2, 2): "B"}
    sync.refresh(now + 0.6, accept_move=True)
    sync.refresh(now + 1.0, accept_move=True)
    assert app.game.current_node.depth == 0 and not sync.ready
    link.board = {}
    sync.refresh(now + 1.1)
    sync.refresh(now + 1.5)
    assert sync.ready
    old = app.game.current_node
    app.game.play(Move((3, 3), player="B"))
    old.set_analysis({"rootInfo": {"visits": 10, "scoreLead": 0, "winrate": 0.5},
                      "moveInfos": [{"move": "Q16", "order": 0, "visits": 10, "scoreLead": 0,
                                     "winrate": 0.5, "pv": ["Q16"]}]})
    link.board = {(3, 3): "B"}
    sync.refresh(now + 1.6)
    sync.refresh(now + 2.0)
    assert sync.ready
    assert not link.points  # Late analysis belongs to old, never current, node.


def test_stale_or_disconnected_sensor_data_disarms(system):
    app, link, sync = system
    link.received = time.monotonic() - 2
    sync.refresh(accept_move=True)
    assert not sync.ready and not sync.armed
    link.received = None
    link.board = {(2, 2): "B"}
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 0
    link.board = {}
    sync.refresh()
    assert sync.ready
    link.board = None
    sync.refresh()
    assert not sync.ready


def test_illegal_suicide_is_rejected_by_katrain(system):
    app, link, sync = system
    app.game.play(Move.from_gtp("A2", player="W"))
    app.game.play(Move.from_gtp("B1", player="W"))
    link.board = {s.coords: s.player for s in app.game.stones}
    sync.refresh()
    link.board[(0, 0)] = "B"
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 2
    assert (0, 0) not in sync.target


@pytest.mark.parametrize("extra_removal", [False, True])
def test_capture_and_removal_in_same_snapshot(system, extra_removal):
    app, link, sync = system
    for gtp, color in [("A2", "B"), ("A1", "W")]:
        app.game.play(Move.from_gtp(gtp, player=color))
    link.board = {s.coords: s.player for s in app.game.stones}
    sync.refresh()
    del link.board[(0, 0)]
    link.board[(1, 0)] = "B"
    if extra_removal:
        del link.board[(0, 1)]
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == (2 if extra_removal else 3)
    assert app.game.prisoner_count["W"] == (0 if extra_removal else 1)
    assert sync.ready is not extra_removal


def test_finished_removal_preserves_position_without_corrections(system):
    app, link, sync = system
    app.game.play(Move((3, 3), "B"))
    app.game.current_node.end_state = "B+R"
    link.board = {}
    sync.refresh(accept_move=True)
    assert app.game.current_node.depth == 1
    assert sync.target == {(3, 3): "B"}
    assert not link.points and not sync.ready
    assert sync.state()["correction"] == {"place": [], "remove": []}
    assert sync.status.startswith("Finished")
