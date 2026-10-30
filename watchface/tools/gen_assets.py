"""Renders the Omnitrix watch face layers (PNG) for the Watch Face Format module.

Geometry and colors mirror OmnitrixFace.kt. Coordinates are in the 450-unit watch face space and
images are rendered at SCALE so they stay crisp on a 480 px screen.

    python3 -I watchface/tools/gen_assets.py
"""

import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "res", "drawable-nodpi")

SIZE = 450                      # watch face coordinate space
SCALE = 480 / 450               # render resolution per unit
OUTER_R = SIZE / 2
R = OUTER_R * 0.95              # face radius inside the bezel
PORTAL_R = 0.8 * R              # portal rim / tunnel mouth
RING_SIZE = 512                 # smoke ring image, larger than the face so puffs aren't cut
INSIDE_SIZE = 650               # portal fog, oversized so it still fills the portal when tilted


def px(v):
    return int(round(v * SCALE))


def argb(c):
    """0xAARRGGBB -> (r, g, b, a) floats 0..1."""
    return ((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255, ((c >> 24) & 255) / 255


def radial(size_units, center, radius, colors, stops):
    """Premultiplied-free RGBA radial gradient, clamped outside the last stop."""
    n = px(size_units)
    ys, xs = np.mgrid[0:n, 0:n].astype(np.float32) / SCALE
    t = np.hypot(xs - center[0], ys - center[1]) / radius
    out = np.zeros((n, n, 4), np.float32)
    cols = np.array([argb(c) for c in colors], np.float32)
    for ch in range(4):
        out[..., ch] = np.interp(t, stops, cols[:, ch])
    return out


def to_image(rgba):
    return Image.fromarray((np.clip(rgba, 0, 1) * 255).astype(np.uint8), "RGBA")


def over(dst, src):
    """Alpha-composite float RGBA src over dst."""
    sa = src[..., 3:4]
    da = dst[..., 3:4]
    oa = sa + da * (1 - sa)
    rgb = (src[..., :3] * sa + dst[..., :3] * da * (1 - sa)) / np.maximum(oa, 1e-6)
    return np.concatenate([rgb, oa], axis=-1)


def save(img, name):
    img.save(os.path.join(OUT, name + ".png"), optimize=True)


def hourglass_poly():
    c = OUTER_R
    return [(c - 0.985 * R, c - 1.2 * R), (c + 0.985 * R, c - 1.2 * R), (c + 0.22 * R, c),
            (c + 0.985 * R, c + 1.2 * R), (c - 0.985 * R, c + 1.2 * R), (c - 0.22 * R, c)]


def disc_mask(n, cx, cy, rad):
    ys, xs = np.mgrid[0:n, 0:n].astype(np.float32) / SCALE
    d = np.hypot(xs - cx, ys - cy)
    return np.clip(rad - d + 0.5, 0, 1)  # ~1 px antialiased edge


def poly_mask(n, pts, width=0):
    """Antialiased polygon fill (width=0) or outline, via 4x supersampling."""
    ss = 4
    m = Image.new("L", (n * ss, n * ss), 0)
    d = ImageDraw.Draw(m)
    p = [(x * SCALE * ss, y * SCALE * ss) for x, y in pts]
    if width:
        d.line(p + [p[0]], fill=255, width=int(width * SCALE * ss), joint="curve")
    else:
        d.polygon(p, fill=255)
    m = m.resize((n, n), Image.LANCZOS)
    return np.asarray(m, np.float32) / 255


# ---------------------------------------------------------------------------------------- layers

def face():
    """Pale green-gray face with the hourglass cut out, black hourglass edges and a thin bezel."""
    n = px(SIZE)
    gray = radial(SIZE, (OUTER_R, OUTER_R), R, [0xFFC4D4B0, 0xFFB0C498, 0xFF8EA676], [0, 0.7, 1])
    inside_face = disc_mask(n, OUTER_R, OUTER_R, R)
    glass = poly_mask(n, hourglass_poly())
    gray[..., 3] = inside_face * (1 - glass)
    border = poly_mask(n, hourglass_poly(), width=0.11 * R) * inside_face
    black = np.zeros_like(gray)
    black[..., 3] = border
    out = over(gray, black)
    bezel = disc_mask(n, OUTER_R, OUTER_R, OUTER_R + 1) * (1 - inside_face)
    black[..., 3] = bezel
    return to_image(over(out, black))


def outside():
    """Green haze spilling off the rim, fading to darkness toward the face edge."""
    return to_image(radial(SIZE, (OUTER_R, OUTER_R), 1.35 * R,
                           [0xFF2E6A0A, 0xFF2E6A0A, 0xFF143004, 0xFF060E02],
                           [0, PORTAL_R / (1.35 * R), 0.75, 1]))


def inside():
    """Luminous fog inside the portal, brightest at the far end. Moves with tilt, so oversized."""
    pr = PORTAL_R
    return to_image(radial(INSIDE_SIZE, (INSIDE_SIZE / 2, INSIDE_SIZE / 2), 1.3 * pr,
                           [0xFFFBFFE0, 0xFFDCFF66, 0xFF9AD82E, 0xFF5AA818, 0xFF3E8410],
                           [0, 0.05, 0.18, 0.45, 0.8]))


def soft_blob(canvas, cx, cy, rad, color):
    """Adds a soft radial blob (alpha falls to 0 at rad) onto a float RGBA canvas, in pixels."""
    n = canvas.shape[0]
    x0, x1 = max(0, int(cx - rad)), min(n, int(cx + rad) + 1)
    y0, y1 = max(0, int(cy - rad)), min(n, int(cy + rad) + 1)
    if x0 >= x1 or y0 >= y1:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    t = np.clip(1 - np.hypot(xs - cx, ys - cy) / rad, 0, 1)
    r, g, b, a = argb(color)
    blob = np.zeros((y1 - y0, x1 - x0, 4), np.float32)
    blob[..., 0], blob[..., 1], blob[..., 2] = r, g, b
    blob[..., 3] = a * t * t
    canvas[y0:y1, x0:x1] = over(canvas[y0:y1, x0:x1], blob)


def smoke_ring():
    """A ring of smoke puffs at the tunnel mouth plus the glowing fog veil inside it."""
    n = px(RING_SIZE)
    c = n / 2
    rad = PORTAL_R * SCALE
    img = radial(RING_SIZE, (RING_SIZE / 2, RING_SIZE / 2), PORTAL_R,
                 [0x38F4FFC4, 0x24E9FF86, 0x00E9FF86], [0, 0.6, 1])
    rnd = random.Random(40)
    puffs = 10
    for j in range(puffs):
        th = j * 2 * math.pi / puffs
        size = rad * 0.8
        px_, py_ = c + math.cos(th) * rad, c + math.sin(th) * rad
        for _ in range(7):
            a = rnd.random() * 2 * math.pi
            dist = rnd.random() * 0.2 * size
            br = (0.14 + rnd.random() * 0.14) * size
            soft_blob(img, px_ + math.cos(a) * dist, py_ + math.sin(a) * dist, br, 0x58F0FFC8)
    return to_image(img)


def depth_fog():
    """Glowing fog thickening toward the far end: deeper smoke sits behind more of it."""
    return to_image(radial(SIZE, (OUTER_R, OUTER_R), 0.75 * PORTAL_R,
                           [0xD0F4FFC4, 0x80E9FF86, 0x30D8FF70, 0x00D8FF70], [0, 0.25, 0.6, 1]))


def mist(seed):
    """Soft blobs of mist, denser toward the middle."""
    n = px(SIZE)
    img = np.zeros((n, n, 4), np.float32)
    rnd = random.Random(seed)
    for _ in range(36):
        a = rnd.random() * 2 * math.pi
        dist = math.sqrt(rnd.random()) * 0.6 * PORTAL_R * SCALE
        br = (0.12 + rnd.random() * 0.24) * PORTAL_R * SCALE
        soft_blob(img, n / 2 + math.cos(a) * dist, n / 2 + math.sin(a) * dist, br, 0x40EEFFB0)
    return to_image(img)


def far_glow():
    """Large soft bloom plus the hot core of the far light."""
    c = (OUTER_R, OUTER_R)
    bloom = radial(SIZE, c, 0.6 * R, [0x90F8FFD0, 0x40E0FF70, 0x00D8FF50], [0, 0.35, 1])
    core = radial(SIZE, c, 0.22 * R, [0xFFFFFFF0, 0x80F0FF90, 0x00D8FF50], [0, 0.35, 1])
    return to_image(over(bloom, core))


def spill():
    """Light bleeding inward from the rim."""
    return to_image(radial(SIZE, (OUTER_R, OUTER_R), PORTAL_R,
                           [0x00D8FF70, 0x00D8FF70, 0x80D8FF70], [0, 0.7, 1]))


def rim_points(phase, scale, t=0.0):
    pr = PORTAL_R * scale
    pts = []
    for k in range(240):
        th = k * 2 * math.pi / 240
        nse = (0.022 * math.sin(9 * th + t * 1.3 + phase) + 0.014 * math.sin(17 * th - t * 2.1 + 2 * phase)
               + 0.009 * math.sin(31 * th + t * 3.7) + 0.006 * math.sin(53 * th - t * 5.3 + phase))
        rr = pr * (1 + nse)
        pts.append((OUTER_R + math.cos(th) * rr, OUTER_R + math.sin(th) * rr))
    return pts


def stroke_layer(pts, width, color, blur=0.0):
    n = px(SIZE)
    m = poly_mask(n, pts, width=width)
    if blur:
        m = np.asarray(Image.fromarray((m * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(blur * SCALE)), np.float32) / 255
    r, g, b, a = argb(color)
    out = np.zeros((n, n, 4), np.float32)
    out[..., 0], out[..., 1], out[..., 2] = r, g, b
    out[..., 3] = m * a
    return out


def rim():
    """Ragged, glowing portal edge: blurred halo, jagged edge and hot core line."""
    pts = rim_points(0.0, 1.0)
    out = stroke_layer(pts, 0.09 * R, 0xAA9CFF3A, blur=0.05 * R)
    out = over(out, stroke_layer(pts, 0.035 * R, 0xEBD6FF7A))
    out = over(out, stroke_layer(pts, 0.012 * R, 0xFFF4FFC4))
    return to_image(out)


def rim_crackle():
    """A second, thinner jagged edge offset from the first; counter-rotates for a crackling look."""
    return to_image(stroke_layer(rim_points(2.3, 1.02, 4.0), 0.012 * R, 0x8CD6FF7A))


def arrow(half_width, tip_r, color):
    """A small arrow on the bezel at 12 o'clock pointing inward, its tip crossing the face edge."""
    n = px(SIZE)
    base = OUTER_R * 0.995
    c = OUTER_R
    pts = [(c, c - tip_r), (c - half_width, c - base), (c + half_width, c - base)]
    fill = poly_mask(n, pts)
    edge = poly_mask(n, pts, width=max(1 / SCALE, 0.008 * R))
    r, g, b, _ = argb(color)
    out = np.zeros((n, n, 4), np.float32)
    out[..., 0], out[..., 1], out[..., 2] = r, g, b
    out[..., 3] = fill
    dark = np.zeros_like(out)
    dark[..., 3] = edge
    return to_image(over(out, dark))


def ambient():
    """Low-power look: dim outlines of the portal rim, hourglass and face edge on black."""
    n = px(SIZE)
    face_disc = disc_mask(n, OUTER_R, OUTER_R, R)
    glass = poly_mask(n, hourglass_poly())
    out = stroke_layer(rim_points(0.0, 1.0), 0.012 * R, 0xFF5E8A1A)
    out[..., 3] *= glass
    out = over(out, stroke_layer(hourglass_poly(), 0.012 * R, 0xFF3A3A3A))
    out[..., 3] *= np.maximum(face_disc, 0)
    ring = disc_mask(n, OUTER_R, OUTER_R, R + 0.006 * R) - disc_mask(n, OUTER_R, OUTER_R, R - 0.006 * R)
    edge = np.zeros_like(out)
    edge[..., 0:3] = 0x3A / 255
    edge[..., 3] = np.clip(ring, 0, 1)
    return to_image(over(out, edge))


def preview(layers):
    """Rough still of the interactive face for the watch face picker."""
    size = layers["face"].size

    def stack(names):
        img = Image.new("RGBA", size, (0, 0, 0, 0))
        for name in names:
            layer = layers[name]
            if layer.size != size:
                off = (layer.size[0] - size[0]) // 2
                layer = layer.crop((off, off, off + size[0], off + size[1]))
            img.alpha_composite(layer)
        return img

    portal = stack(["inside", "smoke_ring", "mist_a", "far_glow", "depth_fog", "spill"])
    clip = disc_mask(size[0], OUTER_R, OUTER_R, PORTAL_R)
    portal.putalpha(Image.fromarray((np.asarray(portal.getchannel("A"), np.float32) * clip).astype(np.uint8)))
    img = Image.new("RGBA", size, (0, 0, 0, 255))
    img.alpha_composite(stack(["outside"]))
    img.alpha_composite(portal)
    for name in ["rim", "face"]:
        img.alpha_composite(stack([name]))
    # Hands at 10:10, the classic display time.
    img.alpha_composite(stack(["hand_hour"]).rotate(-305, resample=Image.BICUBIC))
    img.alpha_composite(stack(["hand_minute"]).rotate(-60, resample=Image.BICUBIC))
    edge = disc_mask(size[0], OUTER_R, OUTER_R, OUTER_R)
    out = np.asarray(img, np.float32)
    out[..., :3] *= edge[..., None]
    return Image.fromarray(out.astype(np.uint8)).convert("RGB")


def main():
    os.makedirs(OUT, exist_ok=True)
    layers = {
        "face": face(),
        "outside": outside(),
        "inside": inside(),
        "smoke_ring": smoke_ring(),
        "depth_fog": depth_fog(),
        "mist_a": mist(3),
        "mist_b": mist(17),
        "far_glow": far_glow(),
        "spill": spill(),
        "rim": rim(),
        "rim_crackle": rim_crackle(),
        "hand_hour": arrow(0.035 * R, 0.99 * R, 0xFFFFFFFF),
        "hand_minute": arrow(0.045 * R, 0.965 * R, 0xFFB4F58C),
        "ambient": ambient(),
    }
    for name, img in layers.items():
        save(img, name)
    save(preview(layers), "preview")


if __name__ == "__main__":
    main()
