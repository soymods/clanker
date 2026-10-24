"""Draws the Clanker icon as 32x32 pixel art and writes it at 1x (source) and 4x (shipped, nearest-neighbor)."""
import struct
import sys
import zlib

PALETTE = {
    '.': None,
    'K': (27, 31, 38),     # outline
    'L': (196, 207, 214),  # metal highlight
    'G': (139, 152, 163),  # metal
    'D': (92, 103, 113),   # metal shadow
    'C': (95, 243, 255),   # eye glow
    'c': (36, 170, 196),   # eye shade
    'W': (245, 250, 252),  # shine / teeth
    'R': (255, 77, 77),    # bulb
    'r': (176, 38, 46),    # bulb shade
    'M': (44, 30, 38),     # mouth
    'O': (184, 100, 44),   # rust
    'o': (130, 66, 30),    # dark rust
    'P': (240, 128, 150),  # blush
}

ART = """
................................
...................KKKK.........
..................KRWRRK........
..................KRRRrK........
...................KKKK.........
..................KGDK..........
.................KGDK...........
.................KGDK...........
.............KKKKGDKKKK.........
............KLLLLGDDDDDK........
.....KKKKKKKKKKKKKKKKKKKKKK.....
....KLLLLLLLLLLLLLLLLLOOOLDK....
....KLGGGGGGGGGGGGGGGGOoOGDK....
....KLGDLGGGGGGGGGGGGGGOGGDK....
....KLGGGKKKKKKGGGGGGGGGGGDK....
..KKKLGGKWWCCCCKGGGGGKKKGGDKKK..
.KDDKLGGKWCCCCCKGGGGKGGGKGDKDDK.
.KGDKLGGKCCCCCCKGGGKGGGGGKDKGDK.
.KGDKLGGKCCCCCcKGGGGGGGGGGDKGDK.
.KDDKLGGKcCCCccKGGGGGGGGGGDKDDK.
..KKKLGGGKKKKKKGGGGGGGGGGGDKKK..
....KLGPPGGGGGGGGGGGGGPPGGDK....
....KLGGGGKKKKKKKKKKKKKGGGDK....
....KLGGGKWWKWWKMMKWWKWKGGDK....
....KLGGGKWWKWWKMMKWWKWKGGDK....
....KLGGGKMMMMMMMMMMMMMKGGDK....
....KLGGGGKMMMMMMMMMMMKGGGDK....
....KLGDLGGKKKKKKKKKKKGGGDLK....
....KLGGGGGGGGGGGGGGGGGGGGDK....
....KDDDDDDDDDDDDDDDDDDDDDDK....
.....KKKKKKKKKKKKKKKKKKKKKK.....
................................
"""


def rows():
    grid = [line for line in ART.strip('\n').split('\n')]
    assert len(grid) == 32, len(grid)
    for i, line in enumerate(grid):
        assert len(line) == 32, (i, len(line), line)
    return grid


def write_png(path, grid, scale):
    size = 32 * scale
    raw = bytearray()
    for line in grid:
        row = bytearray([0])
        for ch in line:
            color = PALETTE[ch]
            px = bytes([0, 0, 0, 0]) if color is None else bytes(color + (255,))
            row += px * scale
        raw += bytes(row) * scale

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


if __name__ == '__main__':
    grid = rows()
    for path, scale in zip(sys.argv[1::2], map(int, sys.argv[2::2])):
        write_png(path, grid, scale)
