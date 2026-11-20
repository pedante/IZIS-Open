import pytest
import socket
import threading
import time

from izis_katrain.transport import BoardLink
from izis_katrain.protocol import Frames


def test_pacing_timeout_and_fragmented_reply():
    client, server = socket.socketpair()
    received = []

    def peer():
        frames = Frames()
        while len(received) < 4:
            for command in frames.feed(server.recv(4096)):
                received.append((command, time.monotonic()))
                # Drop the first query, then fragment all replies.
                if len(received) > 1:
                    response = b"~ALC#" if command == "~RGC#" else b"~SDA" + b"0" * 361 + b"#"
                    server.sendall(response[:7])
                    server.sendall(response[7:])

    thread = threading.Thread(target=peer, daemon=True)
    thread.start()
    link = BoardLink()
    try:
        frames = Frames()
        with pytest.raises(TimeoutError):
            link.request(client, frames, "~STA#", "~SDA")
        assert len(received) == 1  # No retry can queue behind an unacknowledged request.
        assert link.request(client, frames, "~STA#", "~SDA").startswith("~SDA")
        link.request(client, frames, "~RGC#", "~ALC#")
        link.request(client, frames, "~STA#", "~SDA")
        thread.join(2)
        gaps = [b[1] - a[1] for a, b in zip(received, received[1:])]
        assert gaps[0] >= 0.49
        assert gaps[1] >= 0.08
        assert gaps[2] >= 0.24
    finally:
        client.close()
        server.close()
