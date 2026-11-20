"""One socket owner, paced commands, fresh snapshots, automatic reconnect."""

from collections import deque
import socket
import threading
import time

from .protocol import Frames, decode, matrix, validate_command, BUTTONS


class BoardLink:
    def __init__(self, host="127.0.0.1", port=5000, size=19):
        self.address = host, port
        self.size = size
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.snapshot = None
        self.received = 0
        self.generation = 0
        self.error = "disconnected"
        self.active = False
        self.events = deque(maxlen=32)
        self.socket = None
        self.desired = matrix({}, size)
        self.next_send = 0
        self.thread = threading.Thread(target=self.run, daemon=True)

    def start(self):
        if self.active:
            return
        self.stop.clear()
        self.active = True
        self.error = "connecting"
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def read(self):
        with self.lock:
            return self.snapshot, self.received, self.generation, self.error

    def display(self, points):
        with self.lock:
            self.desired = matrix(points, self.size)

    def buttons(self):
        with self.lock:
            events = list(self.events)
            self.events.clear()
            return events

    def request(self, sock, frames, command, response, cleanup=False):
        validate_command(command)
        wait = max(0, self.next_send - time.monotonic())
        if cleanup:
            time.sleep(wait)
        elif self.stop.wait(wait):
            raise OSError("stopped")
        sock.sendall(command.encode("ascii"))
        delay = 0.30 if command[1:4] in ("RGC", "RGF", "FLL") else 0.18
        self.next_send = time.monotonic() + delay
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            sock.settimeout(max(0.001, deadline - time.monotonic()))
            try:
                data = sock.recv(4096)
            except socket.timeout:
                break
            if not data:
                raise OSError("board disconnected")
            answer = None
            for packet in frames.feed(data):
                if packet in BUTTONS and self.active:
                    with self.lock:
                        self.events.append((BUTTONS[packet], time.monotonic(), self.generation))
                if packet.startswith(response):
                    answer = packet
            if answer is not None:
                # Pace from receipt too: network/browser stalls can bunch deliveries.
                self.next_send = time.monotonic() + delay
                return answer
        raise TimeoutError(f"No response to {command[:7]}")

    def run(self):
        while not self.stop.is_set():
            try:
                with socket.create_connection(self.address, timeout=1) as sock:
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    self.socket = sock
                    frames = Frames()
                    self.desired = matrix({}, self.size)
                    with self.lock:
                        self.generation += 1
                        self.events.clear()
                    try:
                        self.request(sock, frames, f"~BOD{self.size:02}#", f"~GBS{self.size:02}#")
                        self.request(sock, frames, "~CTS0#", "~UDS0#")
                        sent = None
                        while not self.stop.is_set():
                            packet = self.request(sock, frames, "~STA#", "~SDA")
                            board = decode(packet, self.size)
                            with self.lock:
                                self.snapshot, self.received, self.error = board, time.monotonic(), ""
                                desired = self.desired
                            if desired != sent:
                                self.request(sock, frames, desired, "~ALS#")
                                sent = desired
                            self.stop.wait(0.12)
                    finally:
                        if self.stop.is_set():
                            self.request(sock, frames, "~RGC#", "~ALC#", cleanup=True)
                            self.request(sock, frames, "~CTS1#", "~UDS1#", cleanup=True)
            except (OSError, ValueError) as exc:
                with self.lock:
                    self.snapshot = None
                    self.error = str(exc)
                self.stop.wait(0.5)
        self.socket = None

    def close(self):
        self.active = False
        self.stop.set()
        if self.thread.is_alive():
            self.thread.join(timeout=4)
        if self.thread.is_alive() and self.socket:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.thread.join(timeout=2)
        if self.thread.is_alive():
            raise RuntimeError("Board worker did not stop")
        with self.lock:
            self.snapshot = None
            self.events.clear()
            self.error = "disconnected"
