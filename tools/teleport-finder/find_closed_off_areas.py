"""Finds areas of the walking map that no teleport can reach, and joins them to the rest. Writes
extra_walks.txt, which build_teleports_and_walks.py adds to walks.txt.

Why: the walking map (collision.bin) and the ways between places (walks.txt) come from the Shortest Path
plugin, which doesn't know every door, web, boss-room barrier or cave entrance. Places behind those look
closed off, and the Teleport Finder can only guess a route out. This script:

 1. Labels every connected walkable area (tiles you can walk between without a door or ladder).
 2. Follows walks.txt out from every teleport landing spot to find the areas teleports can reach.
 3. Joins closed-off areas to a reachable one:
      "O" lines: through an obstacle (climb, cross, squeeze, jump...) that touches both areas. Obstacle
                 locations come from the Golems Don't Die RuneLite plugin's obstacles.gz
                 (github.com/Varzeki/golems-dont-die, BSD 2-Clause, src/main/resources/obstacles.gz).
      "T" lines: across a thin barrier (up to 3 tiles) where there's no obstacle on record. The app treats
                 these as estimates.
    Joins only go one way, from the reachable side into the closed-off area, and repeat until nothing
    more can be joined.

Run it on a walks.txt WITHOUT joins (build_teleports_and_walks.py with an empty extra_walks.txt first),
then rebuild with the new extra_walks.txt. Cave entrances added by hand (CAVES in the build script) close
some areas on their own, so a rerun gives somewhat fewer joins.

Needs numpy and scipy, and several GB of memory. Takes a while (tens of minutes).

Usage: python3 find_closed_off_areas.py collision.bin walks.txt teleports.tsv obstacles.gz extra_walks.txt
"""
import collections, gzip, struct, sys, zlib
import numpy as np
import scipy.ndimage as nd
import scipy.sparse as sp
import scipy.sparse.csgraph as cg

COLLISION, WALKS, TELEPORTS, OBSTACLES, OUT = sys.argv[1:6]
OCEAN = 100_000   # areas bigger than this are open sea: never joined
SMALL = 10        # areas smaller than this aren't worth joining
GAP = 3           # thin barriers up to this many tiles are crossed

# ---------------- 1. Connected areas ----------------

d = open(COLLISION, 'rb').read()
count = struct.unpack('>I', d[4:8])[0]
i = 8
regions = {}
for _ in range(count):
    rx, ry, size, cs = struct.unpack('>HHII', d[i:i + 12])
    regions[(rx, ry)] = zlib.decompress(d[i + 12:i + 12 + cs])
    i += 12 + cs
keys = sorted(regions)
rid = {k: j for j, k in enumerate(keys)}
R = len(keys)
N = np.zeros((R, 4, 64, 64), bool)
E = np.zeros((R, 4, 64, 64), bool)
for k, b in regions.items():
    bits = np.unpackbits(np.frombuffer(b, np.uint8), bitorder='little')
    if bits.size < 4 * 8192:
        bits = np.concatenate([bits, np.zeros(4 * 8192 - bits.size, np.uint8)])
    bits = bits[:4 * 8192].reshape(4, 64, 64, 2)
    N[rid[k]] = bits[..., 0] == 1
    E[rid[k]] = bits[..., 1] == 1

def node(r, z, y, x): return ((r * 4 + z) * 64 + y) * 64 + x

total = R * 4 * 4096
src, dst = [], []
rr, zz, yy, xx = np.nonzero(N)
ok = yy + 1 < 64
src.append(node(rr[ok], zz[ok], yy[ok], xx[ok])); dst.append(node(rr[ok], zz[ok], yy[ok] + 1, xx[ok]))
for r, z, y, x in zip(rr[~ok], zz[~ok], yy[~ok], xx[~ok]):   # into the region to the north
    t = rid.get((keys[r][0], keys[r][1] + 1))
    if t is not None: src.append(np.array([node(r, z, y, x)])); dst.append(np.array([node(t, z, 0, x)]))
rr, zz, yy, xx = np.nonzero(E)
ok = xx + 1 < 64
src.append(node(rr[ok], zz[ok], yy[ok], xx[ok])); dst.append(node(rr[ok], zz[ok], yy[ok], xx[ok] + 1))
for r, z, y, x in zip(rr[~ok], zz[~ok], yy[~ok], xx[~ok]):   # into the region to the east
    t = rid.get((keys[r][0] + 1, keys[r][1]))
    if t is not None: src.append(np.array([node(r, z, y, x)])); dst.append(np.array([node(t, z, y, 0)]))
s = np.concatenate(src); t = np.concatenate(dst)
graph = sp.coo_matrix((np.ones(s.size, np.int8), (s, t)), shape=(total, total)).tocsr()
_, lab = cg.connected_components(graph, directed=False)
walk = np.zeros(total, bool); walk[s] = True; walk[t] = True
sizes = np.bincount(lab[walk])
print('areas labelled')

def comp(x, y, z):
    r = rid.get((x >> 6, y >> 6))
    if r is None or not 0 <= z <= 3: return None
    n = node(r, z, y & 63, x & 63)
    return int(lab[n]) if walk[n] else None

def compnear(x, y, z, rad=1):
    out = set()
    for dy in range(-rad, rad + 1):
        for dx in range(-rad, rad + 1):
            c = comp(x + dx, y + dy, z)
            if c is not None: out.add(c)
    return out

# ---------------- 2. What teleports reach ----------------

edges = collections.defaultdict(set)
for l in open(WALKS, encoding='utf-8'):
    if l[:1] in '@%#' or not l.strip(): continue
    f = list(map(int, l.split()))
    for ca in compnear(f[0], f[1], f[2]):
        edges[ca] |= compnear(f[3], f[4], f[5])
reach = set()
for l in open(TELEPORTS, encoding='utf-8'):
    if l.startswith('#'): continue
    p = l.rstrip('\n').split('\t')
    reach |= compnear(int(p[2]), int(p[3]), int(p[4]), 2)

def spread(start):
    q = list(start)
    while q:
        u = q.pop()
        for v in edges.get(u, ()):
            if v not in reach: reach.add(v); q.append(v)
spread(set(reach))
print('reachable areas', len(reach))

# ---------------- 3a. Joins through obstacles ----------------

b = gzip.open(OBSTACLES).read()
version, n = struct.unpack('>ii', b[:8])
i = 8
joins = []
cands = []
for _ in range(n):
    oid, x, y = struct.unpack('>iHH', b[i:i + 8]); pl, sx, sy, wall, ticks = struct.unpack('>bBBBB', b[i + 8:i + 13]); i += 13
    cx = x + (sx - 1) / 2; cy = y + (sy - 1) / 2
    best = {}
    for yy in range(y - 1, y + sy + 1):
        for xx in range(x - 1, x + sx + 1):
            c = comp(xx, yy, pl)
            if c is None or sizes[c] < SMALL or sizes[c] > OCEAN: continue
            dd = max(abs(xx - cx), abs(yy - cy))
            if c not in best or dd < best[c][0]: best[c] = (dd, xx, yy)
    if len(best) >= 2: cands.append((pl, ticks, best))
changed = True
while changed:
    changed = False
    for pl, ticks, best in cands:
        r = [c for c in best if c in reach]; s_ = [c for c in best if c not in reach]
        if not r or not s_: continue
        a = min(r, key=lambda c: best[c][0])
        for c in s_:
            joins.append(f"O {best[a][1]} {best[a][2]} {pl} {best[c][1]} {best[c][2]} {pl} {max(1, ticks or 2)}")
            reach.add(c); spread({c}); changed = True
print('obstacle joins', len(joins))

# ---------------- 3b. Joins across thin barriers ----------------

X0 = min(k[0] for k in keys) * 64; Y0 = min(k[1] for k in keys) * 64
W = (max(k[0] for k in keys) + 1) * 64 - X0; H = (max(k[1] for k in keys) + 1) * 64 - Y0
thin = 0
for z in range(4):
    G = np.full((H, W), -1, np.int32)
    for r, (kx, ky) in enumerate(keys):
        base = (r * 4 + z) * 4096
        G[ky * 64 - Y0:ky * 64 - Y0 + 64, kx * 64 - X0:kx * 64 - X0 + 64] = np.where(
            walk[base:base + 4096].reshape(64, 64), lab[base:base + 4096].reshape(64, 64), -1)
    while True:
        ok = G >= 0
        reached = np.isin(G, np.fromiter(reach, np.int64)) & ok
        if not reached.any(): break
        dist, (iy, ix) = nd.distance_transform_cdt(~reached, metric='chessboard', return_indices=True)
        ys, xs = np.nonzero(ok & ~reached & (dist <= GAP))
        best = {}
        for c, dd, y, x in zip(G[ys, xs], dist[ys, xs], ys, xs):
            c = int(c)
            if sizes[c] < SMALL or sizes[c] > OCEAN: continue
            if c not in best or dd < best[c][0]: best[c] = (int(dd), int(x), int(y))
        if not best: break
        for c, (dd, x, y) in best.items():
            joins.append(f"T {int(ix[y, x]) + X0} {int(iy[y, x]) + Y0} {z} {x + X0} {y + Y0} {z} {dd}")
            reach.add(c); spread({c}); thin += 1
print('thin-barrier joins', thin)

open(OUT, 'w').write('\n'.join(joins) + '\n')
print('wrote', len(joins), 'joins to', OUT)
