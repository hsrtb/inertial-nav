#!/usr/bin/env python
# Ground-truth test of the gyro integration sign convention in core/py/scratch.
#
# Build a known attitude trajectory C(t) (device -> world) from a chosen body-frame
# omega(t), integrated with tiny substeps so it is effectively exact. Then run the
# scratch algorithm at 450 Hz on the same omega and see what it actually produces.
#
# The gyro reports the rotation of the device frame, so undoing it means rotating
# vectors the SAME way, i.e. by +theta. Using -theta builds R1(-t1)...Rk(-tk), which
# is the inverse factors in the wrong ORDER, and only resembles C^T while the
# increments nearly commute. The table shows that resemblance breaking down as the
# spin gets harder.

import math
import numpy as np

def rodrigues(axis, theta):
    a = np.asarray(axis, float)
    n = np.linalg.norm(a)
    if n < 1e-15: return np.eye(3)
    a = a / n
    c, s = math.cos(theta), math.sin(theta)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) * c + s * K + (1 - c) * np.outer(a, a)

def omega_of(t, hard):
    # body-frame angular velocity, rad/s. 'hard' scales it up to a violent spin.
    return np.array([hard * 1.7 * math.sin(1.3 * t),
                     hard * 2.3 * math.cos(0.7 * t),
                     hard * 1.1 * math.sin(2.1 * t + 0.4)])

def truth(T, dt_fine, hard):
    # correct strapdown: C_{k} = C_{k-1} @ exp([omega^b] dt), omega in BODY coords
    C = np.eye(3)
    t = 0.0
    while t < T:
        w = omega_of(t + 0.5 * dt_fine, hard)
        C = C @ rodrigues(w, np.linalg.norm(w) * dt_fine)
        t += dt_fine
    return C

def scratch_algo(T, dt, hard, sign):
    # exactly do_gyr_integrate(): out = out @ rodrigues(axis, sign * |w| * dt)
    M = np.eye(3)
    n = int(T / dt)
    for k in range(1, n + 1):
        w = omega_of(k * dt, hard)
        norm = np.linalg.norm(w)
        M = M @ rodrigues(w / norm, sign * norm * dt)
    return M

def angle_between(A, B):
    R = A.T @ B
    c = (np.trace(R) - 1) / 2
    return math.degrees(math.acos(max(-1, min(1, c))))

T, dt = 6.0, 1.0 / 450
print("{:>6} {:>14} {:>14} {:>14} {:>14}".format(
    "spin", "-theta vs C", "-theta vs C^T", "+theta vs C", "peak |w|"))
for hard in [0.05, 0.3, 1.0, 3.0]:
    C = truth(T, dt / 50, hard)
    M_neg = scratch_algo(T, dt, hard, -1.0)   # the old sign
    M_pos = scratch_algo(T, dt, hard, +1.0)   # correct: C_k = C_{k-1} @ exp([w^b] dt)
    peak = max(np.linalg.norm(omega_of(k * dt, hard)) for k in range(int(T / dt)))
    print("{:6.2f} {:12.3f}deg {:12.3f}deg {:12.3f}deg {:11.2f}".format(
        hard, angle_between(M_neg, C), angle_between(M_neg, C.T),
        angle_between(M_pos, C), peak))
