#!/usr/bin/env python

# Resample a gyro-integration JSON down to a target rate so the browser viewer can load it.
# Same format in and out, so viewer.html takes either file unchanged.
# usage: ./decimate.py in.json out.json [hz]

import sys, json;

src, dst = sys.argv[1], sys.argv[2];
hz = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0;

data = json.load(open(src));
rate = (len(data) - 1) / (data[-1][0] - data[0][0]);
stride = max(1, round(rate / hz));

# plain subsampling: orientation is smooth at 450 Hz, and averaging rotations
# would need a proper quaternion mean for no visible benefit
out = data[::stride];
if out[-1] is not data[-1]: out.append(data[-1]);

json.dump(out, open(dst, 'w'));
print('{} -> {}: {} samples at {:.1f} Hz -> {} at {:.1f} Hz'.format(
    src, dst, len(data), rate, len(out), rate / stride), file=sys.stderr);
