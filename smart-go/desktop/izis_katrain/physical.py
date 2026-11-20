"""In-process occupancy from the authenticated Android controller; no hardware I/O."""
import time


class PhysicalBoard:
    def __init__(self, size):
        self.size = size
        self.active = False
        self.generation = 0
        self.board = None
        self.received = 0
        self.changed = 0
        self.sequence = -1
        self.stream = None
        self.retired = set()

    def start(self):
        self.active = True

    def close(self):
        self.active = False
        self.board = None
        if self.stream is not None: self.retired.add(self.stream)
        self.stream = None
        self.sequence = -1
        self.generation += 1

    def observe(self, grid, stream, sequence):
        if (not isinstance(grid, list) or len(grid) != 361 or
                any(type(v) is not int or v not in (0, 1, 2) for v in grid) or
                not isinstance(stream, str) or not 1 <= len(stream) <= 80 or
                type(sequence) is not int or sequence < 0):
            raise ValueError('Invalid full-grid observation')
        if stream in self.retired:
            raise ValueError('Retired observation stream')
        if stream != self.stream:
            if self.stream is not None: self.retired.add(self.stream)
            self.generation += 1
            self.stream, self.sequence, self.board = stream, -1, None
        if sequence <= self.sequence:
            raise ValueError('Duplicate or old observation')
        offset = (19 - self.size) // 2
        board = {(i % 19 - offset, i // 19 - offset): 'BW'[v - 1]
                 for i, v in enumerate(grid) if v}
        now = time.monotonic()
        if board != self.board:
            self.changed = now
        self.board, self.received, self.sequence = board, now, sequence
        self.active = True

    def read(self):
        return self.board, self.received, self.generation, ''

    def display(self, points):
        # Screen corrections come from Synchronizer.state; Android owns all LEDs.
        pass

    def buttons(self):
        return ()
