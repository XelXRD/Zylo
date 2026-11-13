"""
Zylo's opening sound, synthesised from scratch (Python standard library only), timed to the House Lights Down intro
(Intro.kt). Writes src/main/res/raw/zylo_intro.wav: 2.6 s, 44.1 kHz, 16-bit mono.

  0.00-0.55  house lights going down: soft room tone fading away
  0.45       the projector switching on: a low clunk with a small click
  0.55-1.20  the beam: projector whir rising, with a faint 24 fps sprocket flutter
  1.00       the Z lights up: a low impact and a warm D major chord with a high shimmer, blooming then decaying

Run from apps/tv:  python tools/intro_sound.py
To use a sound designer's sting instead, replace the WAV (keep the name); the visuals don't depend on this file.
"""
import math, random, struct, wave, os

RATE = 44100
LENGTH = 2.6
N = int(RATE * LENGTH)
random.seed(7)  # the same sound every time it's generated
out = [0.0] * N


def env_adsr(t, start, attack, decay_tc, end=None):
    """0 before start, rises over attack, then exponential decay with time constant decay_tc."""
    if t < start: return 0.0
    dt = t - start
    a = min(1.0, dt / attack) if attack > 0 else 1.0
    a = a * a * (3 - 2 * a)  # smooth attack
    d = math.exp(-max(0.0, dt - attack) / decay_tc)
    if end is not None and t > end: d *= max(0.0, 1 - (t - end) / 0.25)
    return a * d


def fade(t, a, b):
    """Linear 0->1 from a to b."""
    return 0.0 if t <= a else 1.0 if t >= b else (t - a) / (b - a)


# 1. Room tone: brown noise, fading as the lights go down.
brown = 0.0
for i in range(N):
    t = i / RATE
    brown = (brown + random.uniform(-1, 1) * 0.02) * 0.998
    out[i] += brown * 0.3 * (1 - fade(t, 0.05, 0.55)) * fade(t, 0.0, 0.08)

# 2. Projector clunk at 0.45 s: a pitch-dropping thump and a short click.
for i in range(int(0.45 * RATE), int(0.75 * RATE)):
    t = i / RATE
    dt = t - 0.45
    f = 45 + 35 * math.exp(-dt / 0.03)
    phase = 2 * math.pi * (45 * dt + 35 * 0.03 * (1 - math.exp(-dt / 0.03)))
    out[i] += math.sin(phase) * math.exp(-dt / 0.07) * 0.55
    if dt < 0.008: out[i] += random.uniform(-1, 1) * (1 - dt / 0.008) * 0.25

# 3. Projector whir: low-passed noise whose brightness rises, with a 24 Hz sprocket flutter.
lp = 0.0
for i in range(int(0.5 * RATE), int(1.9 * RATE)):
    t = i / RATE
    cutoff = 0.02 + 0.10 * fade(t, 0.55, 1.2)  # one-pole coefficient, rising = brighter
    lp += (random.uniform(-1, 1) - lp) * cutoff
    amp = fade(t, 0.5, 1.0) * (1 - fade(t, 1.25, 1.9)) * 0.75
    flutter = 0.85 + 0.15 * math.sin(2 * math.pi * 24 * t)
    out[i] += lp * amp * flutter

# 4. The Z lights up at 1.0 s: sub impact.
for i in range(int(1.0 * RATE), N):
    t = i / RATE
    dt = t - 1.0
    out[i] += math.sin(2 * math.pi * 41 * dt) * env_adsr(t, 1.0, 0.012, 0.35) * 0.6

# 5. The chord: D major, warm and slightly detuned in pairs; a shimmer an octave and a fifth up arrives a beat later.
chord = [(73.42, 0.30), (110.0, 0.24), (146.83, 0.20), (185.0, 0.14), (220.0, 0.12), (293.66, 0.07)]
shimmer = [(880.0, 0.035), (1174.66, 0.025), (1479.98, 0.018)]
for i in range(int(0.98 * RATE), N):
    t = i / RATE
    dt = t - 0.98
    e = env_adsr(t, 0.98, 0.09, 0.9, end=LENGTH - 0.25)
    s = 0.0
    for f, a in chord:
        s += a * (math.sin(2 * math.pi * f * dt) + 0.6 * math.sin(2 * math.pi * f * 1.004 * dt + 1.3))
        s += a * 0.18 * math.sin(2 * math.pi * f * 2 * dt)  # a little second harmonic for body
    out[i] += s * e * 0.55
    es = env_adsr(t, 1.12, 0.15, 0.6, end=LENGTH - 0.25)
    for f, a in shimmer:
        out[i] += a * math.sin(2 * math.pi * f * dt) * es * (0.8 + 0.2 * math.sin(2 * math.pi * 5.5 * dt))

# Master: gentle saturation, normalise to -3 dBFS, a short fade at each end.
out = [math.tanh(x * 1.2) for x in out]
peak = max(abs(x) for x in out) or 1.0
gain = 10 ** (-3 / 20) / peak
here = os.path.dirname(os.path.abspath(__file__))
dest = os.path.join(here, "..", "src", "main", "res", "raw", "zylo_intro.wav")
os.makedirs(os.path.dirname(dest), exist_ok=True)
with wave.open(dest, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    frames = bytearray()
    for i, x in enumerate(out):
        t = i / RATE
        edge = min(1.0, t / 0.01, (LENGTH - t) / 0.05)
        frames += struct.pack("<h", int(max(-1.0, min(1.0, x * gain * edge)) * 32767))
    w.writeframes(bytes(frames))
print("wrote", os.path.normpath(dest), f"{LENGTH}s")
