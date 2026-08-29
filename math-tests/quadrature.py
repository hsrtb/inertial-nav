#!/usr/bin/env python
# How much do better quadrature and a coning correction actually buy?
#
# Three attitude-propagation schemes, all with the SAME number of steps and the
# same samples, differing only in the rotation vector used for each step:
#   A  right rectangle   phi = w[k] * dt                        (scratch, sign fixed)
#   B  trapezoid         phi = (w[k-1] + w[k])/2 * dt
#   C  trapezoid+coning  phi = B + (dt^2 / 12) * (w[k-1] x w[k])
# Ground truth is the same trajectory integrated with 50x finer substeps.

import math, sys
import numpy as np

def rodrigues(phi):
    """exp([phi]x) for a rotation-vector phi (magnitude = angle)."""
    th = np.linalg.norm(phi)
    if th < 1e-15: return np.eye(3)
    a = phi / th
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) * math.cos(th) + math.sin(th) * K + (1 - math.cos(th)) * np.outer(a, a)

def angle_between(A, B):
    c = (np.trace(A.T @ B) - 1) / 2
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))

def propagate(ws, dts, scheme):
    C = np.eye(3)
    for k in range(1, len(ws)):
        dt = dts[k]
        if scheme == 'A':
            phi = ws[k] * dt
        else:
            phi = 0.5 * (ws[k-1] + ws[k]) * dt
            if scheme == 'C':
                phi = phi + (dt * dt / 12.0) * np.cross(ws[k-1], ws[k])
        C = C @ rodrigues(phi)
    return C

# ---------------------------------------------------------------- synthetic
def omega_of(t, hard):
    return np.array([hard * 1.7 * math.sin(1.3 * t),
                     hard * 2.3 * math.cos(0.7 * t),
                     hard * 1.1 * math.sin(2.1 * t + 0.4)])

def truth(span, hard, fine_dt):
    """Reference solution over [0, span], substeps small enough to be exact."""
    C = np.eye(3)
    m = int(round(span / fine_dt))
    for k in range(m):
        C = C @ rodrigues(omega_of((k + 0.5) * fine_dt, hard) * fine_dt)
    return C

T, dt = 6.0, 1.0 / 450
n = int(T / dt)
print('synthetic trajectory, 450 Hz, %.0f s -- final attitude error vs truth\n' % T)
print('{:>9} {:>10} {:>12} {:>12} {:>12}'.format('peak |w|', 'per step', 'A rect', 'B trapz', 'C +coning'))
for hard in [0.3, 1.0, 3.0, 8.0]:
    ts = [k * dt for k in range(n)]
    ws = [omega_of(t, hard) for t in ts]
    dts = [dt] * n
    # schemes cover [0, ts[-1]]; the reference must cover exactly the same span
    C = truth(ts[-1], hard, dt / 50)
    peak = max(np.linalg.norm(w) for w in ws)
    print('{:9.2f} {:9.2f}deg {:9.4f}deg {:9.4f}deg {:9.4f}deg'.format(
        peak, math.degrees(peak * dt),
        angle_between(propagate(ws, dts, 'A'), C),
        angle_between(propagate(ws, dts, 'B'), C),
        angle_between(propagate(ws, dts, 'C'), C)))

# order of convergence: halve the rate, see how the error scales
print('\nconvergence at peak |w| = 8.4 rad/s (error should fall as dt^1 for A, dt^2 for B/C)\n')
print('{:>8} {:>12} {:>12} {:>12}'.format('rate Hz', 'A rect', 'B trapz', 'C +coning'))
for div in [1, 2, 4, 8]:
    d = dt * div
    m = int(T / d)
    ts = [k * d for k in range(m)]
    ws = [omega_of(t, 3.0) for t in ts]
    C = truth(ts[-1], 3.0, dt / 50)
    e = [angle_between(propagate(ws, [d]*m, s), C) for s in 'ABC']
    print('{:8.1f} {:9.4f}deg {:9.4f}deg {:9.4f}deg'.format(1/d, *e))

# ---------------------------------------------------------------- real data
# optional: pass a recorder CSV to see what the schemes do on real gyro samples,
# where the rectangle-vs-trapezoid difference telescopes to a boundary term and
# so mostly vanishes for a capture that starts and ends at rest
if len(sys.argv) < 2:
    print('\n(pass a recorder CSV to also run the schemes on real data)')
    sys.exit(0)

path = sys.argv[1]
gyr = []
with open(path) as f:
    f.readline()
    for line in f:
        if line[0] == '#': continue
        a = line.rstrip('\n').split(',')
        if a[0] != 'gyr': continue
        gyr.append((int(a[1]) / 1e9, np.array([float(a[3]), float(a[4]), float(a[5])])))

ts = [g[0] - gyr[0][0] for g in gyr]
ws = [g[1] for g in gyr]
dts = [0.0] + [ts[k] - ts[k-1] for k in range(1, len(ts))]
res = {s: propagate(ws, dts, s) for s in 'ABC'}

print('\n%s' % path.split('/')[-1])
print('%d gyr samples, %.2f s, %.1f Hz, peak |w| = %.2f rad/s (%.0f deg/s), max step %.2f deg' % (
    len(ws), ts[-1], (len(ws)-1)/ts[-1],
    max(np.linalg.norm(w) for w in ws), math.degrees(max(np.linalg.norm(w) for w in ws)),
    math.degrees(max(np.linalg.norm(ws[k]) * dts[k] for k in range(1, len(ws))))))
for s in 'ABC':
    th = angle_between(res[s], np.eye(3))
    print('  %s final attitude: %7.3f deg from identity' % (s, th))
print('  A vs B: %.4f deg   B vs C: %.4f deg   A vs C: %.4f deg' % (
    angle_between(res['A'], res['B']), angle_between(res['B'], res['C']),
    angle_between(res['A'], res['C'])))
