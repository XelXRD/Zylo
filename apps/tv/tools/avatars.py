"""
Zylo's profile avatars: 16 original character archetypes, written out as Android vector drawables
(res/drawable/avatar_*.xml). Run from apps/tv:  python tools/avatars.py

One visual system for all of them, so they read as a set:
- 96x96, shown clipped to a circle.
- A deep background with a diagonal key-light beam from the top right (the "cinematic" part).
- Chunky rounded silhouettes, big simple eyes (the "playful" part).
- A rim of light on the right edge of each head, from the same key light.
Archetypes only: no real characters, logos or emblems.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "res", "drawable")
INK = "#15161A"


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def circle(cx, cy, r):
    return f"M{f(cx - r)},{f(cy)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 Z"


def ellipse(cx, cy, rx, ry):
    return f"M{f(cx - rx)},{f(cy)} a{f(rx)},{f(ry)} 0 1,0 {f(2 * rx)},0 a{f(rx)},{f(ry)} 0 1,0 {f(-2 * rx)},0 Z"


def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{f(x + r)},{f(y)} h{f(w - 2 * r)} a{f(r)},{f(r)} 0 0,1 {f(r)},{f(r)} v{f(h - 2 * r)} "
            f"a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(r)} h{f(-(w - 2 * r))} a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(-r)} "
            f"v{f(-(h - 2 * r))} a{f(r)},{f(r)} 0 0,1 {f(r)},{f(-r)} Z")


def poly(*pts):
    return "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts) + " Z"


# Elements: ("fill", path, colour) | ("stroke", path, colour, width) | ("rim", path, shifted, base, light)
def fill(p, c):
    return ("fill", p, c)


def stroke(p, c, w=2.0):
    return ("stroke", p, c, w)


def rim_circle(cx, cy, r, base, light):
    """A head lit from the right: the light colour shows as a crescent on its right edge."""
    return ("rim", circle(cx, cy, r), circle(cx - 2.6, cy + 0.6, r), base, light)


def rim_rrect(x, y, w, h, r, base, light):
    return ("rim", rrect(x, y, w, h, r), rrect(x - 2.6, y + 0.6, w, h, r), base, light)


SHOULDERS = "M12,96 C12,78 28,70 48,70 C68,70 84,78 84,96 Z"


def scene(bg, beam):
    return [fill("M0,0 H96 V96 H0 Z", bg), fill(poly((34, 0), (96, 0), (96, 62)), beam)]


def eyes(y=46, lx=41, rx=55, pupil=INK, rxs=3.4, rys=4.2):
    return [fill(ellipse(lx, y, rxs, rys), "#FFFFFF"), fill(ellipse(rx, y, rxs, rys), "#FFFFFF"),
            fill(circle(lx + 0.9, y + 0.7, 2.0), pupil), fill(circle(rx + 0.9, y + 0.7, 2.0), pupil)]


def smile(path="M43,55 Q48,59 53,55", c="#3A1E14", w=2.0):
    return [stroke(path, c, w)]


def blush(y=53, c="#66FF7A8A"):
    return [fill(circle(36.5, y, 3), c), fill(circle(59.5, y, 3), c)]


AVATARS = {}

# Caped hero: cape, suit, a plain shield on the chest, a kiss curl.
SKIN = "#E3A577"
AVATARS["hero"] = scene("#23408F", "#2D4FA8") + [
    fill("M6,96 L16,64 Q48,54 80,64 L90,96 Z", "#D7263D"),
    fill(SHOULDERS, "#2C5BD0"),
    fill(poly((41, 80), (55, 80), (48, 91)), "#F4C430"),
    fill(rrect(43, 58, 10, 14, 3), "#C98B60"),
    rim_circle(48, 44, 18, SKIN, "#F8CFA6"),
    fill("M30,43 C29,27 39,22 50,22 C62,22 68,30 66,41 C60,34 52,32 44,34 C39,35 34,38 30,43 Z", "#2A1B14"),
    stroke("M48,32 q-3.5,4.5 1,7", "#2A1B14", 2.4),
] + eyes(46) + blush(54) + smile("M43,54 Q49,58.5 54,53.5")

# Hooded vigilante: deep hood, domino mask, blank lenses.
AVATARS["vigilante"] = scene("#15201A", "#1E2E25") + [
    fill(SHOULDERS, "#24432F"),
    fill("M22,96 L24,52 C24,26 36,18 48,18 C60,18 72,26 72,52 L74,96 Z", "#2F5D3A"),
    stroke("M60,21 C69,27 72,40 72,52", "#4E8A5E", 2.2),
    fill(ellipse(48, 48, 18, 21), "#1B3323"),
    rim_circle(48, 49, 15, "#C68B59", "#E3AE7F"),
    fill("M33,45 Q48,38 63,45 L62,52 Q48,47 34,52 Z", "#121417"),
    fill(ellipse(42, 46.5, 3.4, 2.1), "#FFFFFF"), fill(ellipse(54, 46.5, 3.4, 2.1), "#FFFFFF"),
] + smile("M44,57 Q49,59 53,55.5", "#5A2E1C")

# Speedster: winged helmet, lenses, bolt, motion lines.
AVATARS["speedster"] = scene("#A3301A", "#BC3D24") + [
    stroke("M6,30 h12", "#E2604A", 2.4), stroke("M3,40 h11", "#E2604A", 2.4), stroke("M7,50 h8", "#E2604A", 2.4),
    fill(SHOULDERS, "#C8302A"),
    fill(poly((51, 73), (42, 84), (48, 84), (44, 95), (57, 81), (50, 81), (55, 73)), "#FFD23F"),
    fill(poly((31, 40), (18, 31), (22, 37), (15, 40), (24, 44), (31, 47)), "#FFD23F"),
    fill(poly((65, 40), (78, 31), (74, 37), (81, 40), (72, 44), (65, 47)), "#FFD23F"),
    rim_circle(48, 45, 19, "#D6352C", "#F2735A"),
    fill("M35,52 C35,60 41,65 48,65 C55,65 61,60 61,52 Z", "#8D5A3B"),
    fill(poly((35, 44), (45, 41.5), (44.5, 47.5), (36, 48.5)), "#FFFFFF"),
    fill(poly((51, 41.5), (61, 44), (60, 48.5), (51.5, 47.5)), "#FFFFFF"),
] + smile("M43,57 Q48,60.5 53,57")

# Heroine: long hair streaming in the wind, domino mask, a star on the suit.
HAIR = "#B8432A"
AVATARS["heroine"] = scene("#7A1F5C", "#92286E") + [
    fill("M24,74 C19,52 23,28 46,23.5 C64,21 75,34 72,50 C74,58 80,63 88,64 C80,70 71,69 66,64 L66,74 Z", HAIR),
    stroke("M73,52 C76,58 81,61 86,62", "#D9653F", 1.8),
    fill(SHOULDERS, "#1FA39A"),
    fill(poly((48, 78), (49.6, 82), (53.8, 82.4), (50.6, 85), (51.6, 89), (48, 86.8), (44.4, 89), (45.4, 85), (42.2, 82.4), (46.4, 82)), "#FFD23F"),
    fill(rrect(43.5, 58, 9, 14, 3), "#7A4D3A"),
    rim_circle(48, 45, 16.5, "#8A5A44", "#B27C60"),
    fill("M31,45 C30,30 38,25 48,25 C58,25 66,31 65,43 C58,37 50,33 42,34 C37,36 33,40 31,45 Z", HAIR),
    stroke("M58,27.5 C65,31 68,38 66,44", "#D9653F", 1.8),
    fill("M33,44 Q40,40 48,43 Q56,40 63,44 L61.5,50.5 Q55,52.5 48,48.5 Q41,52.5 34.5,50.5 Z", "#1FA39A"),
] + eyes(46.5, rxs=3.0, rys=3.4) + blush(54.5, "#55FF7A8A") + smile("M43.5,55.5 Q48,59 52.5,55.5")

# Robot: boxy head, screen face, antenna, chest lights.
AVATARS["robot"] = scene("#1D4E5F", "#25627A") + [
    fill(rrect(18, 74, 60, 30, 9), "#8FA3AD"),
    fill(rrect(35, 80, 26, 12, 3), "#2B3A42"),
    fill(circle(41, 86, 2.1), "#FF5A5F"), fill(circle(48, 86, 2.1), "#FFD23F"), fill(circle(55, 86, 2.1), "#4CD3C2"),
    fill(rrect(42, 62, 12, 14, 2), "#6C7F89"),
    stroke("M48,27 V16", "#6C7F89", 3), fill(circle(48, 13, 4.2), "#FF5A5F"),
    fill(circle(25, 46, 4.2), "#6C7F89"), fill(circle(71, 46, 4.2), "#6C7F89"),
    rim_rrect(26, 26, 44, 40, 11, "#B8C7CE", "#EEF3F5"),
    fill(rrect(32, 35, 32, 18, 7), "#1A2328"),
    fill(circle(41, 44, 4.6), "#4CD3C2"), fill(circle(55, 44, 4.6), "#4CD3C2"),
    fill(circle(42.6, 42.4, 1.4), "#FFFFFF"), fill(circle(56.6, 42.4, 1.4), "#FFFFFF"),
    stroke("M41,59 h14", "#6C7F89", 2.6),
]

# Wizard: pointed hat with a star, big white beard.
AVATARS["wizard"] = scene("#3B2A6B", "#4A3782") + [
    fill(SHOULDERS, "#5B3FA6"),
    rim_circle(48, 47, 15, "#F0C3A0", "#FFE0C7"),
    fill("M32,51 C32,71 40,83 48,88 C56,83 64,71 64,51 C58,58 38,58 32,51 Z", "#ECE8DF"),
    fill("M40,55 Q44,51.5 48,54.5 Q52,51.5 56,55 Q52,58 48,56 Q44,58 40,55 Z", "#D6D0C2"),
    fill("M30,39 C34,25 41,15 55,4 C53,15 58,28 66,39 Z", "#2A1C5A"),
    stroke("M55,6 C54,16 58,28 65,38", "#6A55B8", 1.8),
    fill("M20,41 Q48,31 76,41 Q72,46 48,40 Q24,46 20,41 Z", "#22164B"),
    fill(poly((48, 24), (49.4, 27.6), (53, 28), (50.2, 30.3), (51.1, 34), (48, 32), (44.9, 34), (45.8, 30.3), (43, 28), (46.6, 27.6)), "#FFD23F"),
    stroke("M38,43.5 L44,42.5", "#ECE8DF", 2.4), stroke("M52,42.5 L58,43.5", "#ECE8DF", 2.4),
] + eyes(47.5, rxs=3.0, rys=3.6)

# Ninja: wrapped hood, eye strip, red headband streaming in the wind.
AVATARS["ninja"] = scene("#2D4778", "#38558C") + [
    fill(SHOULDERS, "#1E2028"),
    fill("M66,35 C74,32 80,37 88,31 L86,39 C79,43 72,40 66,41 Z", "#D7263D"),
    fill("M66,39 C72,42 76,46 84,46 L80,51 C74,50 70,46 66,43 Z", "#A81D30"),
    rim_circle(48, 46, 19, "#23252E", "#4A4E60"),
    fill(rrect(30, 40, 36, 13, 6.5), "#D9A27A"),
    fill(rrect(29, 33, 38, 5, 2.5), "#D7263D"),
] + eyes(46.5, rxs=3.4, rys=3.0) + [
    stroke("M37,41.5 L45,43.5", "#1E2028", 2.4), stroke("M59,41.5 L51,43.5", "#1E2028", 2.4),
]

# Vampire: high collar with red lining, widow's peak, pointed ears, tiny fangs.
PALE = "#E6DCD6"
AVATARS["vampire"] = scene("#3A0F1E", "#4E1428") + [
    fill("M14,96 L20,58 L34,70 L48,74 L62,70 L76,58 L82,96 Z", "#12090D"),
    fill("M25,64 L34,72 L48,76 L62,72 L71,64 L69,96 L27,96 Z", "#9E1B32"),
    fill(poly((40, 76), (56, 76), (48, 96)), "#EDE6E0"),
    fill(poly((32, 45), (25, 37), (33, 51)), PALE), fill(poly((64, 45), (71, 37), (63, 51)), PALE),
    rim_circle(48, 45, 17, PALE, "#FFFFFF"),
    fill("M31,43 C30,29 38,25 48,25 C58,25 66,29 65,43 C60,37 54,35 48,41 C42,35 36,37 31,43 Z", "#17121A"),
] + eyes(47, pupil="#C8102E") + [
    stroke("M42,55 Q48,57.5 54,55", "#5A1A24", 1.8),
    fill(poly((43.6, 55.6), (45.8, 55.9), (44.6, 59.2)), "#FFFFFF"),
    fill(poly((50.2, 55.9), (52.4, 55.6), (51.4, 59.2)), "#FFFFFF"),
]

# Detective: fedora, trench coat, the hat's shadow across the eyes.
AVATARS["detective"] = scene("#2E2A24", "#3D372E") + [
    fill(SHOULDERS, "#9C7A4C"),
    fill(poly((36, 71), (48, 84), (42, 96), (28, 96)), "#866640"),
    fill(poly((60, 71), (48, 84), (54, 96), (68, 96)), "#866640"),
    fill(poly((44, 76), (52, 76), (48, 84)), "#EDE6E0"),
    rim_circle(48, 49, 16, "#B97A57", "#DDA27E"),
] + eyes(48) + [
    fill("M31,39 H65 V47 C58,45 38,45 31,47 Z", "#59000000"),
    fill("M32,37 C32,23 38,18 48,18 C58,18 64,23 64,37 Z", "#3A322B"),
    fill(rrect(32, 31, 32, 5, 1), "#1A1612"),
    fill(ellipse(48, 37.5, 27, 5), "#2B2520"),
    stroke("M57,20 C62,24 64,30 64,36", "#5A4F44", 1.8),
] + smile("M44,56.5 Q48,58 52,56.5", "#5A2E1C", 1.8)

# Pirate: tricorn with gold trim, eyepatch, red coat, a gold tooth.
AVATARS["pirate"] = scene("#0E4D5C", "#136478") + [
    fill(SHOULDERS, "#8B2E2E"),
    fill(circle(40, 84, 2), "#E0B13A"), fill(circle(40, 91, 2), "#E0B13A"),
    fill(circle(32, 54, 6), "#2A1A10"), fill(circle(64, 54, 6), "#2A1A10"),
    rim_circle(48, 48, 16, "#A86B47", "#CC8C66"),
] + [fill(ellipse(41, 47, 3.4, 4.2), "#FFFFFF"), fill(circle(41.9, 47.7, 2.0), INK)] + [
    stroke("M33,41 L66,51", "#111111", 1.6),
    fill(circle(55, 47.5, 4.8), "#111111"),
    stroke("M42,55 Q48,60 54,55", "#3A1E14", 2.0),
    fill(rrect(49.5, 56.3, 2.6, 2.6, 0.6), "#E0B13A"),
    fill("M17,37 Q26,29 33,30 Q40,13 48,13 Q56,13 63,30 Q70,29 79,37 Q48,27 17,37 Z", "#1A1412"),
    stroke("M20,36 Q48,26.5 76,36", "#E0B13A", 1.6),
]

# Knight: rounded helm, visor slit with eyes glinting, red plume.
AVATARS["knight"] = scene("#3E4A57", "#4E5D6D") + [
    fill(SHOULDERS, "#9AA7B3"),
    fill(circle(22, 82, 11), "#B7C2CC"), fill(circle(74, 82, 11), "#B7C2CC"),
    fill("M49,26 C46,14 55,5 67,7 C60,10 57,17 57,27 Z", "#D7263D"),
    rim_rrect(29, 24, 38, 46, 17, "#B7C2CC", "#EEF2F5"),
    fill(rrect(33, 42, 30, 6, 3), "#1D232A"),
    fill(ellipse(41.5, 45, 2.4, 1.6), "#FFFFFF"), fill(ellipse(54.5, 45, 2.4, 1.6), "#FFFFFF"),
    stroke("M48,50 V64", "#8C99A5", 1.8),
    fill(circle(42, 58, 1.2), "#6E7B87"), fill(circle(42, 62, 1.2), "#6E7B87"),
    fill(circle(54, 58, 1.2), "#6E7B87"), fill(circle(54, 62, 1.2), "#6E7B87"),
]

# Alien: tall green head, huge eyes, two antennae.
AVATARS["alien"] = scene("#2B1A4F", "#3A2468") + [
    fill(SHOULDERS, "#A9B4C2"),
    fill(rrect(42, 60, 12, 14, 4), "#62B847"),
    stroke("M41,21 L34,8", "#7ED957", 2.6), stroke("M55,21 L62,8", "#7ED957", 2.6),
    fill(circle(34, 7, 3.6), "#FFD23F"), fill(circle(62, 7, 3.6), "#FFD23F"),
    ("rim", ellipse(48, 42, 20, 24), ellipse(45.4, 42.6, 20, 24), "#7ED957", "#BDF59A"),
    fill(ellipse(39, 44, 6.2, 8.6), "#111111"), fill(ellipse(57, 44, 6.2, 8.6), "#111111"),
    fill(circle(40.8, 40.6, 2.1), "#FFFFFF"), fill(circle(58.8, 40.6, 2.1), "#FFFFFF"),
    fill(circle(48, 58, 2.3), "#2E5A1C"),
]

# Astronaut: white helmet, dark visor with a reflection, eyes peeking out, stars.
AVATARS["astronaut"] = scene("#0F1B3D", "#16285A") + [
    fill(circle(14, 18, 1.2), "#FFFFFF"), fill(circle(22, 32, 0.9), "#FFFFFF"), fill(circle(10, 52, 1.0), "#FFFFFF"),
    fill(SHOULDERS, "#E9EDF2"),
    fill(circle(35, 85, 4), "#D7263D"),
    fill(rrect(40, 76, 16, 10, 2), "#B9C2CD"),
    rim_circle(48, 45, 22, "#F3F5F8", "#FFFFFF"),
    fill(rrect(31, 33, 34, 25, 12.5), "#1B2B52"),
    stroke("M36,38 Q46,34 57,37", "#6C8BD6", 2.4),
    fill(circle(60, 40, 1.6), "#9FB6EC"),
] + eyes(47.5, rxs=2.8, rys=3.4)

# Monster: purple furball, horns, one big eye, toothy grin.
AVATARS["monster"] = scene("#1F4B3F", "#285F50") + [
    fill(SHOULDERS, "#8E5BD9"),
    fill(poly((31, 31), (25, 13), (38, 26)), "#F1E3C6"), fill(poly((65, 31), (71, 13), (58, 26)), "#F1E3C6"),
    rim_circle(48, 48, 22, "#9D6AE8", "#C6A4F5"),
    fill(poly((40, 27), (44, 20), (47, 27)), "#9D6AE8"), fill(poly((48, 26), (52, 19), (55, 27)), "#9D6AE8"),
    fill(circle(48, 44, 9.5), "#FFFFFF"),
    fill(circle(49.6, 45.2, 4.8), INK), fill(circle(51.2, 43.2, 1.6), "#FFFFFF"),
    fill("M36,57 Q48,69 60,57 Z", "#2A1438"),
    fill(poly((41.5, 57.4), (45, 57.6), (43.2, 61.4)), "#FFFFFF"),
    fill(poly((51, 57.6), (54.5, 57.4), (52.8, 61.4)), "#FFFFFF"),
]

# Ghost: soft sheet, shaded away from the light, rosy cheeks.
AVATARS["ghost"] = scene("#1C2340", "#262F57") + [
    fill(circle(80, 74, 1.3), "#C9D3F5"), fill(circle(86, 62, 0.9), "#C9D3F5"),
    fill("M24,96 L24,46 C24,30 34,22 48,22 C62,22 72,30 72,46 L72,96 Z", "#F2F4FA"),
    fill("M24,96 L24,46 C24,33 31,25 41,22.6 C34,29 30,38 30,50 L30,96 Z", "#D5DAEB"),
    fill(ellipse(41, 46, 3.6, 5.6), "#1C2340"), fill(ellipse(55, 46, 3.6, 5.6), "#1C2340"),
    fill(circle(42.2, 44, 1.2), "#FFFFFF"), fill(circle(56.2, 44, 1.2), "#FFFFFF"),
    fill(ellipse(48, 58, 3.0, 3.8), "#1C2340"),
] + blush(54, "#80F7A8B8")

# Racer: striped helmet, dark visor with a glint, racing suit.
AVATARS["racer"] = scene("#C2410C", "#D9531A") + [
    fill(SHOULDERS, "#111318"),
    fill(rrect(40, 73, 4.5, 26, 2), "#F7F7F7"), fill(rrect(51.5, 73, 4.5, 26, 2), "#F7F7F7"),
    rim_circle(48, 46, 20, "#F7F7F7", "#FFFFFF"),
    ("clipfill", circle(48, 46, 20), rrect(41, 24, 5, 44, 0), "#D7263D"),
    ("clipfill", circle(48, 46, 20), rrect(50, 24, 5, 44, 0), "#D7263D"),
    fill("M30,44 Q48,36 66,44 L64,55 Q48,50.5 32,55 Z", "#14161C"),
    stroke("M36,45 Q46,41 56,43", "#5B6B8C", 1.8),
    fill(circle(60, 45.5, 1.4), "#8A9AC0"),
]

# Cowboy: wide curled brim, red bandana, denim, sunset light.
AVATARS["cowboy"] = scene("#8A4B1F", "#A35B27") + [
    fill(SHOULDERS, "#3D6E8F"),
    fill(poly((35, 70), (61, 70), (48, 83)), "#C8102E"),
    rim_circle(48, 49, 16, "#C68642", "#E5A868"),
] + eyes(49) + blush(56, "#55FF6A5A") + smile("M43,57 Q48,61 53,57") + [
    fill("M32,40 C32,26 36,18 42,20 C45,21 47,24 48,24 C49,24 51,21 54,20 C60,18 64,26 64,40 Z", "#7A4E2A"),
    fill(rrect(32, 35, 32, 4, 1), "#3E2614"),
    fill("M12,36 C20,42 34,44 48,44 C62,44 76,42 84,36 C80,45 66,48.5 48,48.5 C30,48.5 16,45 12,36 Z", "#6B4423"),
    stroke("M56,20 C61,23 63,30 64,39", "#9A6A3E", 1.8),
]

ORDER = ["hero", "heroine", "vigilante", "speedster", "robot", "wizard", "ninja", "vampire", "detective",
         "pirate", "knight", "alien", "astronaut", "monster", "ghost", "racer", "cowboy"]


def xml(elements):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated by tools/avatars.py: edit there, not here. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="96dp" android:height="96dp"'
           ' android:viewportWidth="96" android:viewportHeight="96">']
    for e in elements:
        kind = e[0]
        if kind == "fill":
            out.append(f'  <path android:fillColor="{e[2]}" android:pathData="{e[1]}" />')
        elif kind == "stroke":
            out.append(f'  <path android:strokeColor="{e[2]}" android:strokeWidth="{e[3]}" android:strokeLineCap="round"'
                       f' android:strokeLineJoin="round" android:pathData="{e[1]}" />')
        elif kind == "rim":
            _, shape, shifted, base, light = e
            out.append("  <group>")
            out.append(f'    <clip-path android:pathData="{shape}" />')
            out.append(f'    <path android:fillColor="{light}" android:pathData="{shape}" />')
            out.append(f'    <path android:fillColor="{base}" android:pathData="{shifted}" />')
            out.append("  </group>")
        elif kind == "clipfill":
            _, clip, shape, colour = e
            out.append("  <group>")
            out.append(f'    <clip-path android:pathData="{clip}" />')
            out.append(f'    <path android:fillColor="{colour}" android:pathData="{shape}" />')
            out.append("  </group>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    assert set(ORDER) == set(AVATARS)
    for name in ORDER:
        with open(os.path.join(OUT, f"avatar_{name}.xml"), "w", encoding="utf-8") as fh:
            fh.write(xml(AVATARS[name]))
    print(f"wrote {len(ORDER)} avatars")
