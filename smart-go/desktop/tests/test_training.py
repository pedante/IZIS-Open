import time
import socket
import threading

import pytest
from test_sync import system
from izis_katrain.protocol import Frames, matrix, validate_command, BUTTONS
from izis_katrain.transport import BoardLink
from katrain.core.game import Move


def analysis(node):
    node.set_analysis({'rootInfo': {'visits': 20, 'scoreLead': 0, 'winrate': .5},
        'policy': [1 / 361] * 361 + [0],
        'moveInfos': [{'move': m, 'order': i, 'visits': 4, 'scoreLead': -i*.1,
                      'winrate': .5, 'pv': [m]} for i,m in enumerate(['D4','Q16','D16','Q4','K10'])]})


def test_button_modes_and_position_invalidation(system):
    app, link, sync = system
    analysis(app.game.current_node)
    sync.refresh()
    assert not link.points
    def press(event): sync.button(BUTTONS[event], time.monotonic(), link.generation)
    press('~WKY#'); assert len(link.points) == 1
    press('~WKY#'); assert not link.points
    press('~WTK#'); assert len(link.points) == 5 and set(link.points.values()) == {1}
    press('~BKY#'); assert len(link.points) == 5
    press('~BTK#'); assert len(link.points) == 361
    assert matrix(link.points,19).startswith('~SAW')
    app.game.play(Move((3,3),player='B'))
    sync.refresh(); assert sync.hint is None
    press('~WKY#'); assert sync.hint is None  # correction outstanding
    link.board = {(3,3):'B'}; sync.refresh()
    analysis(app.game.current_node)
    sync.button('five', 0, link.generation); assert sync.hint is None
    sync.button('five', time.monotonic(), link.generation-1); assert sync.hint is None
    press('~WTK#'); assert (3,3) not in link.points
    link.generation += 1; sync.refresh(); assert sync.hint is None
    link.active = False; sync.refresh(); press('~WKY#'); assert sync.hint is None


@pytest.mark.parametrize('command', ['~SAR'+'1'*361+'#','~SAL'+'1'*361+'#',
    '~SAW'+'3'*361+'#','~SAW1#','~SHP001,r999g000b000,1#','~FLL#'])
def test_unsafe_commands_rejected(command):
    with pytest.raises(ValueError): validate_command(command)


def test_matrix_rejects_invalid_palette():
    for color in (-1,3,255,1.5,True):
        with pytest.raises(ValueError): matrix({(0,0):color},19)
    validate_command(matrix({(x,y):1 for x in range(19) for y in range(19)},19))


def test_buttons_are_not_lost_beside_ack():
    client,server=socket.socketpair()
    link=BoardLink(); link.active=True
    def peer():
        server.recv(1024)
        server.sendall(b'~WKY#~SDA'+b'0'*361+b'#~WTK#')
    t=threading.Thread(target=peer);t.start()
    try:
        assert link.request(client,Frames(),'~STA#','~SDA').startswith('~SDA')
        assert [e[0] for e in link.buttons()] == ['best','five']
    finally: client.close();server.close();t.join()


@pytest.mark.parametrize("during_setup", [False, True])
def test_inactive_lifecycle_and_clean_disconnect(during_setup):
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.settimeout(.15)
    link=BoardLink(port=listener.getsockname()[1]);commands=[]
    assert not link.active and not link.thread.is_alive()
    with pytest.raises(socket.timeout): listener.accept()
    def peer():
        conn,_=listener.accept();frames=Frames()
        with conn:
            while data:=conn.recv(4096):
                for cmd in frames.feed(data):
                    commands.append(cmd)
                    reply={'~BOD19#':'~GBS19#','~CTS0#':'~UDS0#','~CTS1#':'~UDS1#',
                           '~RGC#':'~ALC#','~STA#':'~SDA'+'0'*361+'#'}.get(cmd,'~ALS#')
                    conn.sendall(reply.encode())
    listener.settimeout(3)
    thread=threading.Thread(target=peer);thread.start();link.start()
    deadline=time.monotonic()+3
    while time.monotonic()<deadline:
        if (commands if during_setup else link.read()[0] is not None): break
        time.sleep(.02)
    assert commands if during_setup else link.read()[0] == {}
    link.close();thread.join(3);listener.close()
    assert commands[-2:] == ['~RGC#','~CTS1#']
    assert not link.active and not link.thread.is_alive() and link.read()[0] is None
    before=len(commands);time.sleep(.2);assert len(commands)==before
