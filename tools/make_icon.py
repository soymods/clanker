"""Draws Clanker's mod icon as 32x32 pixel art, scaled up with nearest-neighbour (pure Python, no Pillow).

Usage: python3 tools/make_icon.py src/main/resources/assets/clanker/icon.png@4 [other.png@16 ...]
"""
import struct
import sys
import zlib

N = 32
img = [[(0, 0, 0, 0) for _ in range(N)] for _ in range(N)]


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def px(x, y, c):
    if 0 <= x < N and 0 <= y < N:
        img[y][x] = c


def rect(x0, y0, x1, y1, c):  # inclusive
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(x, y, c)


# ---- background tile: rounded square, vertical stepped gradient ----
bands = ['#2b3a55', '#283650', '#25324b', '#222e46', '#1f2a41', '#1c263c', '#192237', '#171f33']
for y in range(N):
    c = hexc(bands[min(len(bands) - 1, y * len(bands) // N)])
    for x in range(N):
        px(x, y, c)
# round the corners (radius ~3 px)
for (cx, cy) in [(0, 0), (N - 1, 0), (0, N - 1), (N - 1, N - 1)]:
    sx = 1 if cx == 0 else -1
    sy = 1 if cy == 0 else -1
    for (dx, dy) in [(0, 0), (1, 0), (2, 0), (0, 1), (0, 2), (1, 1)]:
        px(cx + sx * dx, cy + sy * dy, (0, 0, 0, 0))
OUT = hexc('#11151c')
HI = hexc('#d9dee4')
LIGHT = hexc('#b4bcc5')
METAL = hexc('#939ca7')
SHADE = hexc('#707984')
DARK = hexc('#555d67')

# ---- antenna ----
rect(15, 4, 16, 7, OUT)
rect(15, 4, 15, 7, LIGHT)
rect(16, 4, 16, 7, SHADE)
rect(14, 1, 17, 4, OUT)
rect(15, 2, 16, 3, hexc('#ff6b3d'))
px(15, 2, hexc('#ffc2a8'))

# ---- ears / bolts ----
for x0 in (3, 26):
    rect(x0, 15, x0 + 2, 21, OUT)
    rect(x0 + (1 if x0 == 3 else 0), 16, x0 + (1 if x0 == 3 else 0) + 1, 20, SHADE)
    rect(x0 + (1 if x0 == 3 else 0), 16, x0 + (1 if x0 == 3 else 0) + 1, 16, LIGHT)

# ---- head: a block, lit from the top-left ----
rect(5, 8, 26, 29, OUT)
rect(6, 9, 25, 28, METAL)
rect(6, 9, 25, 9, HI)
rect(6, 9, 6, 28, LIGHT)
rect(7, 10, 24, 10, LIGHT)
rect(25, 10, 25, 28, SHADE)
rect(7, 28, 25, 28, DARK)
# panel seam + rivets
for x in (8, 23):
    px(x, 11, DARK)
    px(x, 26, DARK)

# ---- visor ----
VIS = hexc('#0d1117')
VIS2 = hexc('#161d27')
rect(7, 12, 24, 18, OUT)
rect(8, 13, 23, 17, VIS)
rect(8, 13, 23, 13, VIS2)
E0, E1, E2, E3 = hexc('#0f5d73'), hexc('#1fb6d9'), hexc('#5fe6ff'), hexc('#e9fdff')
# left eye: wide open
rect(9, 14, 13, 17, E0)
rect(10, 14, 12, 16, E1)
rect(10, 15, 12, 15, E2)
px(10, 14, E3)
px(11, 14, E2)
# right eye: half-closed, unimpressed
rect(18, 16, 22, 17, E0)
rect(19, 16, 22, 16, E1)
px(19, 16, E3)
px(20, 16, E2)
# visor glint
px(22, 13, hexc('#2a3646'))
px(21, 13, hexc('#2a3646'))

# ---- mouth: a speaker grille showing a voice waveform ----
rect(9, 20, 22, 26, OUT)
rect(10, 21, 21, 25, hexc('#1a1f27'))
A0, A1 = hexc('#ff8a3d'), hexc('#ffd27a')
for x, h in [(11, 1), (13, 3), (15, 5), (16, 5), (18, 3), (20, 1)]:
    top = 23 - h // 2
    rect(x, top, x, top + h - 1, A0)
    px(x, top, A1)

def write_png(path, scale):
    size = N * scale
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        row = img[y // scale]
        for x in range(size):
            raw.extend(row[x // scale])

    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    data += chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(data)


if __name__ == '__main__':
    for arg in sys.argv[1:]:
        path, scale = arg.split('@')
        write_png(path, int(scale))
