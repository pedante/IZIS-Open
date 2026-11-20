"""Izis wire coordinates are right-to-left, top-to-bottom; Go uses bottom-left."""


BUTTONS = {"~WKY#": "best", "~WTK#": "five", "~BKY#": "reserved", "~BTK#": "policy"}

def index(coords, size):
    x, y = coords
    if size not in (9, 13, 19) or not (0 <= x < size and 0 <= y < size):
        raise ValueError("Invalid board coordinate")
    return (size - 1 - y) * size + size - 1 - x


def coords(i, size):
    if size not in (9, 13, 19) or not 0 <= i < size * size:
        raise ValueError("Invalid sensor index")
    return size - 1 - i % size, size - 1 - i // size


def decode(packet, size):
    data = packet[4:-1]
    if not packet.startswith("~SDA") or not packet.endswith("#"):
        raise ValueError("Not a board snapshot")
    if len(data) != size * size or set(data) - set("012"):
        raise ValueError("Invalid snapshot length or stone color")
    return {coords(i, size): "BW"[int(v) - 1] for i, v in enumerate(data) if v != "0"}


def matrix(points, size):
    """Use a single atomic, dim matrix write; no accumulated/stale LED commands."""
    if size not in (9, 13, 19) or any(type(c) is not int or c not in (1, 2) for c in points.values()):
        raise ValueError("Only low-brightness green/white matrix output is allowed")
    data = ["0"] * 361
    offset = (19 - size) // 2
    if size != 19:
        for r in range(-1, size + 1):
            for c in range(-1, size + 1):
                if r in (-1, size) or c in (-1, size):
                    data[(r + offset) * 19 + c + offset] = "1"
    for point, color in points.items():
        i = index(point, size)
        data[(i // size + offset) * 19 + i % size + offset] = str(color)
    # Always use SAW, including >50 LEDs; the threshold is a precaution, not a rating.
    return "~SAW" + "".join(data) + "#"


class Frames:
    def __init__(self):
        self.buffer = ""

    def feed(self, data):
        self.buffer += data.decode("ascii", errors="strict")
        packets = []
        while "#" in self.buffer:
            part, self.buffer = self.buffer.split("#", 1)
            if "~" in part:
                packets.append("~" + part.rsplit("~", 1)[1] + "#")
        self.buffer = "~" + self.buffer.rsplit("~", 1)[1] if "~" in self.buffer else ""
        if len(self.buffer) > 512:
            self.buffer = ""
            raise ValueError("Oversized Izis frame")
        return packets


def validate_command(command):
    """The production writer accepts only the deliberately small safe command set."""
    if command in {'~STA#', '~CTS0#', '~CTS1#', '~BOD09#', '~BOD13#', '~BOD19#', '~RGC#', '~RGF#'}:
        return
    if command.startswith('~SAW') and command.endswith('#') and len(command) == 366:
        if set(command[4:-1]) <= set('012'):
            return
    raise ValueError('Command rejected by LED/transport safety policy')
