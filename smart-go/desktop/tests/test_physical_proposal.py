from test_control import session, request
from test_sync import system
from katrain.core.game import Move


def proposal(control, point=(3, 3), color='B', stones=None, **overrides):
    grid = [0] * 361
    for (x, y), c in (stones or {point: color}).items():
        grid[y * 19 + x] = 1 if c == 'B' else 2
    return dict(client='phone', id='proposal', op='physical_proposal',
                game_id=control.game_id, revision=control.revision,
                controller_epoch=control.epoch, point=list(point), color=color,
                physical=grid) | overrides


def test_proposal_bypasses_equality_gate_and_is_idempotent(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    request(control, 'acquire')
    link.board = {(3, 3): 'B'}
    sync.refresh()
    assert not sync.ready
    command = proposal(control)
    result = control.execute(command)
    assert result['proposal']['accepted']
    assert result['stones'] == [[3, 3, 'B']]
    assert control.execute(command) == result
    assert app.game.current_node.depth == 1
    assert not control.execute(command | {'id': 'stale'})['proposal']['accepted']
    assert app.game.current_node.depth == 1


def test_rejects_wrong_epoch_color_extra_and_removal(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    request(control, 'acquire')
    for i, fields in enumerate([{'controller_epoch': 'old'}, {'color': 'W'},
                               {'physical': [0] * 360},
                               {'stones': {(3, 3): 'B', (4, 4): 'B'}}]):
        result = control.execute(proposal(control, id=str(i), **fields))
        assert not result['proposal']['accepted']
        assert app.game.current_node.depth == 0


def test_capture_and_unrelated_removal(system, tmp_path):
    app, link, sync, control = session(system, tmp_path)
    app.game.play(Move((0, 1), 'B'))
    app.game.play(Move((0, 0), 'W'))
    request(control, 'acquire')
    invalid = proposal(control, point=(1, 0), stones={(1, 0): 'B'})
    assert not control.execute(invalid)['proposal']['accepted']
    assert app.game.current_node.depth == 2
    valid = proposal(control, point=(1, 0), stones={(0, 1): 'B', (1, 0): 'B'}, id='capture')
    assert control.execute(valid)['proposal']['accepted']
    assert app.game.prisoner_count['W'] == 1


def test_request_id_cannot_change_meaning(system, tmp_path):
    import pytest
    from izis_katrain.control import ControlError
    app, link, sync, control = session(system, tmp_path)
    request(control, 'acquire')
    command = proposal(control)
    assert control.execute(command)['proposal']['accepted']
    with pytest.raises(ControlError, match='different content'):
        control.execute(command | {'point': [4, 4]})
    assert app.game.current_node.depth == 1


def test_real_observation_boundary_stability_and_retired_stream(system, tmp_path):
    import pytest
    from izis_katrain.physical import PhysicalBoard
    app, _, sync, control = session(system, tmp_path)
    link = PhysicalBoard(19)
    app.board_link = sync.link = link
    request(control, 'acquire')
    command = proposal(control)
    link.observe(command['physical'], 'first', 1)
    assert not control.execute(command)['proposal']['accepted']
    link.changed -= .7
    assert control.execute(command | {'id': 'stable'})['proposal']['accepted']
    link.close()
    with pytest.raises(ValueError, match='Retired'):
        link.observe(command['physical'], 'first', 2)
    link.observe(command['physical'], 'second', 1)
    with pytest.raises(ValueError, match='Duplicate'):
        link.observe(command['physical'], 'second', 1)
    assert not hasattr(link, 'request')  # No raw MCU transport exists at this boundary.
