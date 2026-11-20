"""Reconcile occupancy only. KaTrain remains the sole rule and game authority."""

import math
import time

from katrain.core.game import IllegalMoveException, Move


class Synchronizer:
    def __init__(self, app, link, debounce=0.35):
        self.app, self.link, self.debounce = app, link, debounce
        self.revision = 0
        self.hint = None
        self.position_changed = time.monotonic()
        self.node = None
        self.game = None
        self.target = {}
        self.observed = None
        self.changed = 0
        self.generation = None
        self.armed = False
        self.ready = False
        self.status = "Waiting for board"

    def refresh(self, now=None, accept_move=False):
        now = time.monotonic() if now is None else now
        game = self.app.game
        node = game.current_node
        if game is not self.game or node is not self.node:
            self.hint = None
            self.revision += 1
            self.position_changed = now
            self.game, self.node = game, node
            self.target = {stone.coords: stone.player for stone in game.stones}
            self.armed = False
        if not getattr(self.link, "active", True):
            self.hint = None
            self.armed = self.ready = False
            self.status = "Disconnected"
            return
        physical, received, generation, error = self.link.read()
        if generation != self.generation or physical is None or now - received > (30 if hasattr(self.link, "observe") else 1.5):
            self.hint = None
            self.generation = generation
            self.observed = None
            self.armed = self.ready = False
        if physical is None or now - received > (30 if hasattr(self.link, "observe") else 1.5):
            self.status = f"Board unavailable: {error or 'stale snapshot'}"
            self.link.display({})
            return
        if physical != self.observed:
            self.observed = dict(physical)
            self.changed = now
        if game.end_result:
            self.hint = None
            self.armed = self.ready = False
            self.status = "Finished — remove stones at leisure"
            self.link.display({})
            return
        stable = now - self.changed >= self.debounce
        matches = physical == self.target
        self.ready = stable and matches
        if self.ready:
            self.armed = True
            self.status = "Synchronized"
        elif stable and accept_move and self.armed:
            additions = {p: c for p, c in physical.items() if self.target.get(p) != c}
            removals = {p for p, c in self.target.items() if physical.get(p) != c}
            self.armed = False
            if (
                len(additions) == 1
                and self.app.next_player_info.human and not game.end_result
                and not game.insert_mode
            ):
                point, color = next(iter(additions.items()))
                if color == node.next_player:
                    try:
                        move = Move(point, player=color)
                        if removals:
                            # Reuse KaTrain's rules for snapshots containing capture + removal.
                            with game._lock:
                                try:
                                    game._validate_move_and_update_chains(move, False)
                                    if not removals <= {s.coords for s in game.last_capture}:
                                        raise IllegalMoveException("Unrelated stones were removed")
                                finally:
                                    game._calculate_groups()
                        game.play(move, expected_node=node)
                    except IllegalMoveException as exc:
                        self.status = f"Illegal move: {exc}"
                    else:
                        self.refresh(now)
                        return
            self.status = "Correct board to match KaTrain (undo in KaTrain first)"
        elif not matches:
            self.status = "Place indicated stones / remove extra stones"
        if not matches:
            points = {p: 2 for p, c in physical.items() if self.target.get(p) != c}
            points.update({p: 1 for p, c in self.target.items() if physical.get(p) != c})
        elif self.ready:
            points = self.hint_points()
        else:
            points = {}
        self.link.display(points)

    def button(self, event, received, generation):
        self.refresh()
        if not self.ready or generation != self.generation or received < self.position_changed:
            return
        mode = event if event in ("best", "five", "policy") else None
        if mode:
            self.hint = None if self.hint == mode else mode
            self.refresh()

    def legal(self, move):
        game = self.app.game
        with game._lock:
            try:
                game._validate_move_and_update_chains(move, False)
                return True
            except IllegalMoveException:
                return False
            finally:
                game._calculate_groups()

    def hint_points(self):
        node = self.node
        if not self.hint or not node.analysis_exists:
            return {}
        if self.hint == "policy":
            size = self.app.game.board_size[0]
            policy = node.policy or []
            if len(policy) != size * size + 1:
                return {}
            positive = [p for p in policy[:-1] if math.isfinite(p) and p > 0]
            peak = max(positive, default=0)
            points = {}
            for i, value in enumerate(policy[:-1]):
                point = (i % size, size - 1 - i // size)
                if peak and math.isfinite(value) and value >= peak * 0.05 and point not in self.target:
                    if self.legal(Move(point, player=node.next_player)):
                        points[point] = 1 if value >= peak * 0.25 else 2
            return points
        points = {}
        candidates = node.candidate_moves
        best = candidates[0].get("pointsLost", 0) if candidates else 0
        for candidate in candidates:
            move = Move.from_gtp(candidate["move"], player=node.next_player)
            if not move.is_pass and move.coords not in self.target and self.legal(move):
                points[move.coords] = 1 if candidate.get("pointsLost", best) - best <= 1.0 else 2
                if len(points) >= (1 if self.hint == "best" else 5):
                    break
        return points

    def state(self):
        node = self.app.game.current_node
        return {
            "position_revision": self.revision, "board_size": list(self.app.game.board_size),
            "score_lead_black": node.score, "win_probability_black": node.winrate,
            "last_move_points_lost": node.points_lost,
            "correction": {"place": [], "remove": []} if self.app.game.end_result else {"place": [[*p,c] for p,c in self.target.items() if (self.observed or {}).get(p) != c],
                           "remove": [[*p,c] for p,c in (self.observed or {}).items() if self.target.get(p) != c]},
            "active": getattr(self.link, "active", True), "hint": self.hint,
            "status": self.status, "ready": self.ready, "depth": node.depth,
            "next_player": node.next_player,
            "move": node.move.gtp() if node.move else None,
            "stones": [[*p, c] for p, c in sorted(self.target.items())],
            "candidates": [m["move"] for m in node.candidate_moves[:5]],
            "analysis_visits": node.root_visits,
        }
