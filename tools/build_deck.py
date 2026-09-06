#!/usr/bin/env python3
"""
Builds the ClashFit pitch deck.

Ten slides, 16:9, light, carrying the app's ember. Every screenshot and every video sits inside the
iQOO 15 — the manufacturer's own render with the display punched out, the same asset the landing
page uses — so the deck shows the product on the phone it was built for rather than a floating
rectangle of UI.

Every number on a slide comes from the FACTS block and was verified against the codebase. Text is
laid out by flow: each block estimates the height it will occupy and the next block starts below
it, because PowerPoint lets a fixed box overflow silently onto whatever is beneath.

    python3 tools/build_deck.py [--zombie A.mp4 B.mp4] [--walk C.mp4] [--pdf]
"""

import argparse
import math
import os
import subprocess

from PIL import Image, ImageDraw
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Emu, Inches, Pt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "deck-assets")
FRAMED = os.path.join(ASSETS, "framed")

# ── palette: paper, ink, and the app's ember ──────────────────────────────────────────────
GROUND = RGBColor(0xFA, 0xF9, 0xF7)
PANEL = RGBColor(0xF0, 0xEE, 0xEA)
PANEL_HI = RGBColor(0xE4, 0xE1, 0xDB)
EDGE = RGBColor(0xDD, 0xD9, 0xD2)
EMBER = RGBColor(0xE0, 0x44, 0x18)       # 4.9:1 on paper
INK = RGBColor(0x14, 0x16, 0x1A)
MUTED = RGBColor(0x51, 0x56, 0x5D)       # 7.9:1
FAINT = RGBColor(0x6E, 0x73, 0x7A)       # 5.8:1
SUCCESS = RGBColor(0x1F, 0x7A, 0x45)
SCREEN_BLACK = (0x05, 0x05, 0x06, 255)
STATUS_BAND = 0.062      # of frame height: a status bar's worth of black above the app, holding the camera
LINE = 1.2               # PowerPoint's line box is about 1.2x the font size before any line spacing

W, H = Inches(13.333), Inches(7.5)
EMU_PER_IN = 914400

# ── every figure on a slide, verified against the codebase ────────────────────────────────
FACTS = {
    "tests": "942",
    "lines": "41,268",
    "screens": "29",
    "modes": "18",
    "model": "Gemma 3n E2B · int4 · 3.1 GB",
    "cold_start": "494 ms",
}

# ── the iQOO 15, measured off the manufacturer's render ───────────────────────────────────
#
# Both assets have the display cut out and a transparent background. The screen rectangle and the
# punch-hole are the site's own measurements (index.html, .phone__screen / .duo__screen), given as
# fractions of the frame so they hold at any size. "front" is the handset face on; "duo" is the
# same handset with its back panel beside it, for the one slide where the hardware is the point.
FRAME = {
    "front": dict(png=os.path.join(ASSETS, "frame", "front.png"), w=720, h=1530,
                  screen=(0.03333, 0.01438, 1 - 0.03333, 1 - 0.01699),
                  hole=(0.4986, 0.0438, 0.052)),
    "duo": dict(png=os.path.join(ASSETS, "frame", "duo.png"), w=1314, h=1530,
                screen=(0.46727, 0.01438, 1 - 0.02131, 1 - 0.01699),
                hole=(0.7222, 0.0438, 0.0365)),
}


def _screen_px(kind):
    f = FRAME[kind]
    l, t, r, b = f["screen"]
    return int(f["w"] * l), int(f["h"] * t), int(f["w"] * r), int(f["h"] * b)


def composite(kind, screenshot):
    """
    A screenshot behind the phone's glass.

    The frame is pasted last, so its bezel and rounded corners mask the screenshot's edges the way
    the real glass masks a real display. The screenshot is scaled to cover the display and centre
    cropped — the app's own screenshots are already the phone's aspect, so the crop is a few
    pixels at most. The punch-hole is drawn over the screenshot because on the real device the
    camera sits in the display, not in the bezel.
    """
    os.makedirs(FRAMED, exist_ok=True)
    out = os.path.join(FRAMED, f"{kind}-{os.path.basename(screenshot)}")
    if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(screenshot):
        return out
    f = FRAME[kind]
    frame = Image.open(f["png"]).convert("RGBA")
    canvas = Image.new("RGBA", (f["w"], f["h"]), (0, 0, 0, 0))

    x0, y0, x1, y1 = _screen_px(kind)
    sw, sh = x1 - x0, y1 - y0

    # The app draws below the status bar on the real device, so the camera cut-out sits in a black
    # band above the content rather than on top of it. The screenshots are rendered without a
    # status bar — the first version put the hole straight over the boss's name.
    band = int(f["h"] * STATUS_BAND)
    d = ImageDraw.Draw(canvas)
    d.rectangle((x0, y0, x1, y1), fill=SCREEN_BLACK)

    # The whole screenshot, fitted into what the band leaves. Covering the area instead cropped
    # the bottom row of tiles off the fight screen — the reps and the combo, which is the point of
    # the picture. Fitting leaves a few pixels of black either side, and the app's own ground is
    # near enough to black that nobody will find the seam.
    shot = Image.open(screenshot).convert("RGB")
    ah = sh - band
    scale = min(sw / shot.width, ah / shot.height)
    shot = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)
    canvas.paste(shot, (x0 + (sw - shot.width) // 2, y0 + band + (ah - shot.height) // 2))

    hx, hy, hw = f["hole"]
    r = f["w"] * hw / 2
    d.ellipse((f["w"] * hx - r, f["h"] * hy - r, f["w"] * hx + r, f["h"] * hy + r), fill=(0x1A, 0x1B, 0x1F, 255))

    canvas.alpha_composite(frame)
    canvas.save(out)
    return out


# ── primitives ────────────────────────────────────────────────────────────────────────────

def slide(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    bg = s.shapes.add_shape(1, 0, 0, W, H)
    bg.fill.solid()
    bg.fill.fore_color.rgb = GROUND
    bg.line.fill.background()
    bg.shadow.inherit = False
    return s


def text(s, x, y, w, h, copy, size=18, color=INK, bold=False, align=PP_ALIGN.LEFT, spacing=1.15):
    tb = s.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    for i, line in enumerate(copy.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = spacing
        r = p.add_run()
        r.text = line
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = "Verdana"
    return tb


def _lines(copy, size_pt, width_emu, tight=False):
    """
    How many lines this text will take.

    python-pptx cannot measure text and PowerPoint lets a box overflow silently, so every block
    estimates its own height. The factors are the average advance width as a fraction of point
    size, measured off a render rather than guessed: the bold face fit about twenty characters at
    38pt in 7.1in, which is 0.68; body copy came out near 0.52.
    """
    factor = 0.68 if tight else 0.52
    per_line = max(1, int((width_emu / EMU_PER_IN * 72.0) / (size_pt * factor)))
    return sum(max(1, math.ceil(len(p) / per_line)) for p in copy.split("\n"))


def _height(n, size, spacing):
    """n lines of this size at this spacing, as PowerPoint will actually lay them out."""
    return Inches(n * size * LINE * spacing / 72.0)


def heading(s, x, y, w, copy, size=40):
    """Reserves the height it will occupy and returns where it ends."""
    n = _lines(copy, size, w, tight=True)
    h = _height(n, size, 1.0)
    text(s, x, y, w, h, copy, size=size, bold=True, spacing=1.0)
    return y + h


def body(s, x, y, w, copy, size=16, color=None, spacing=1.4):
    n = _lines(copy, size, w)
    h = _height(n, size, spacing)
    text(s, x, y, w, h, copy, size=size, color=MUTED if color is None else color, spacing=spacing)
    return y + h


def kicker(s, x, y, label):
    text(s, x, y, Inches(6), Inches(0.3), label.upper(), size=11, color=EMBER, bold=True)


def card(s, x, y, w, h, fill=PANEL):
    sh = s.shapes.add_shape(5, x, y, w, h)
    sh.fill.solid()
    sh.fill.fore_color.rgb = fill
    sh.line.color.rgb = EDGE
    sh.line.width = Pt(1)
    sh.shadow.inherit = False
    sh.adjustments[0] = 0.06
    return sh


def card_text(s, x, y, w, copy, size=15, color=INK, pad=Inches(0.3), spacing=1.4):
    """A card sized to its text, drawn first so the text sits on it. Returns where it ends."""
    n = _lines(copy, size, w - 2 * pad)
    th = _height(n, size, spacing)
    card(s, x, y, w, th + 2 * pad)
    text(s, x + pad, y + pad, w - 2 * pad, th, copy, size=size, color=color, spacing=spacing)
    return y + th + 2 * pad


def row_card(s, x, y, w, title, detail, title_w=Inches(3.0), title_color=INK, size=11.5):
    """A titled row: bold label left, one or two lines of detail right. Returns where it ends."""
    n = _lines(detail, size, w - title_w - Inches(0.7))
    h = max(Inches(0.62), _height(n, size, 1.25) + Inches(0.32))
    card(s, x, y, w, h)
    text(s, x + Inches(0.35), y + Inches(0.16), title_w - Inches(0.2), Inches(0.4), title, size=13, bold=True, color=title_color)
    text(s, x + title_w + Inches(0.15), y + Inches(0.17), w - title_w - Inches(0.5), h - Inches(0.3), detail, size=size, color=MUTED, spacing=1.25)
    return y + h


def stat(s, x, y, w, value, label):
    text(s, x, y, w, Inches(0.7), value, size=40, bold=True, color=EMBER, align=PP_ALIGN.CENTER)
    text(s, x, y + Inches(0.72), w, Inches(0.4), label.upper(), size=10, color=MUTED, align=PP_ALIGN.CENTER)
    return y + Inches(1.15)


def a(p):
    return os.path.join(ASSETS, p)


# ── the phone ─────────────────────────────────────────────────────────────────────────────

def device(s, img, x, y, height, label=None, kind="front"):
    """A screenshot inside the iQOO 15, at the frame's own aspect. Returns its width."""
    f = FRAME[kind]
    w = Emu(int(height * f["w"] / f["h"]))
    s.shapes.add_picture(composite(kind, img), x, y, width=w, height=height)
    if label:
        text(s, x, y + height + Inches(0.14), w, Inches(0.3), label, size=10, color=MUTED, align=PP_ALIGN.CENTER)
    return w


def _ffmpeg():
    """The static ffmpeg in the throwaway venv, since the machine has none and pip refuses."""
    local = os.path.join(ROOT, ".venv-deck", "bin", "python")
    if os.path.exists(local):
        out = subprocess.run([local, "-c", "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())"],
                             capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    return "ffmpeg"


def _poster(path):
    """A real frame from two seconds in, cached beside the assets."""
    poster = os.path.join(ASSETS, os.path.basename(path) + ".poster.jpg")
    if not os.path.exists(poster):
        try:
            subprocess.run([_ffmpeg(), "-y", "-ss", "2", "-i", path, "-frames:v", "1", "-q:v", "3", poster],
                           capture_output=True, check=True)
        except (OSError, subprocess.CalledProcessError):
            return None
    return poster if os.path.exists(poster) else None


def video_device(s, x, y, height, path, caption):
    """
    A video playing inside the iQOO 15.

    Layered the way the site layers it: the display area is filled black, the clip sits on that
    fitted to the display without distortion, and the frame — with its transparent cut-out — is
    drawn over both. PowerPoint draws pictures above media, so the bezel masks the clip's edges and
    the poster shows through the glass until somebody presses play. A clip shot at 9:16 lands with
    a thin black band above and below; stretching it to fit would be the wrong kind of honest.
    """
    f = FRAME["front"]
    fw = Emu(int(height * f["w"] / f["h"]))
    l, t, r, b = f["screen"]
    sx, sy = x + Emu(int(fw * l)), y + Emu(int(height * t))
    sw, sh = Emu(int(fw * (r - l))), Emu(int(height * (b - t)))

    glass = s.shapes.add_shape(5, sx, sy, sw, sh)
    glass.fill.solid()
    glass.fill.fore_color.rgb = RGBColor(0x05, 0x05, 0x06)
    glass.line.fill.background()
    glass.shadow.inherit = False
    glass.adjustments[0] = 0.04

    if path and os.path.exists(path):
        poster = _poster(path)
        ratio = None
        if poster:
            with Image.open(poster) as im:
                ratio = im.width / im.height
        ratio = ratio or 9 / 16
        if ratio > sw / sh:                      # wider than the glass: bands above and below
            vw, vh = sw, Emu(int(sw / ratio))
        else:                                    # taller: bands at the sides
            vw, vh = Emu(int(sh * ratio)), sh
        vx, vy = sx + (sw - vw) // 2, sy + (sh - vh) // 2
        s.shapes.add_movie(path, vx, vy, vw, vh, poster_frame_image=poster, mime_type="video/mp4")
    else:
        text(s, sx, sy + sh // 2 - Inches(0.3), sw, Inches(0.6), "▶", size=28, color=FAINT, align=PP_ALIGN.CENTER)

    # The camera cut-out, so a video phone and a screenshot phone are the same phone.
    hx, hy, hw = f["hole"]
    hd = Emu(int(fw * hw))
    hole = s.shapes.add_shape(9, x + Emu(int(fw * hx)) - hd // 2, y + Emu(int(height * hy)) - hd // 2, hd, hd)
    hole.fill.solid()
    hole.fill.fore_color.rgb = RGBColor(0x1A, 0x1B, 0x1F)
    hole.line.fill.background()
    hole.shadow.inherit = False

    s.shapes.add_picture(f["png"], x, y, width=fw, height=height)
    text(s, x - Inches(0.5), y + height + Inches(0.14), fw + Inches(1.0), Inches(0.3), caption,
         size=10, color=MUTED, align=PP_ALIGN.CENTER)
    return fw


# ── the deck ──────────────────────────────────────────────────────────────────────────────

def build(zombie, walk):
    prs = Presentation()
    prs.slide_width, prs.slide_height = W, H
    L = Inches(0.9)                      # the left margin every slide shares
    FULL = W - 2 * L                     # full content width

    # ── 1 · title: the handset, front and back ────────────────────────────────────────────
    s = slide(prs)
    ph = Inches(6.1)
    pw = Emu(int(ph * FRAME["duo"]["w"] / FRAME["duo"]["h"]))
    px = W - Inches(0.6) - pw
    device(s, a("51-fight-landing.png"), px, Inches(0.7), ph, kind="duo")
    col = px - L - Inches(0.5)
    y = heading(s, L, Inches(1.9), col, "CLASHFIT", size=72)
    y = body(s, L, y + Inches(0.1), col,
             "Your body is the controller.\nYour camera is the referee.", size=24, color=EMBER, spacing=1.3)
    y = body(s, L, y + Inches(0.45), col,
             "A fitness game where a clean rep does more damage than a sloppy one — "
             "because the phone measured the difference.", size=14)
    text(s, L, Inches(6.55), col, Inches(0.4),
         "Team Da Goats  ·  Omkar Kadam · Ujjwal Pardeshi  ·  iQOO Hackathon 2026", size=12, color=MUTED)
    text(s, px, Inches(0.7) + ph + Inches(0.12), pw, Inches(0.3), "iQOO 15", size=10, color=MUTED, align=PP_ALIGN.CENTER)

    # ── 2 · the problem ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.8), "the problem")
    y = heading(s, L, Inches(1.2), FULL, "Fitness apps count what you tell them.", size=42) + Inches(0.4)
    col = Inches(5.4)
    yl = body(s, L, y, col,
              "You type in three sets of ten. The app believes you.\n\n"
              "It never saw the reps, so it cannot know whether they were deep, controlled, "
              "or worth anything at all — and neither can you.")
    yr = card_text(s, Inches(6.9), y - Inches(0.1), Inches(5.5),
                   "So the number that motivates you is the one number nobody checked.\n\n"
                   "Make the camera the referee and every number becomes evidence.", size=17)
    body(s, L, max(yl, yr) + Inches(0.5), FULL,
         "ClashFit scores depth, range, tempo and alignment on every single rep, on the phone, "
         "and pays you in damage for the good ones.", size=16, color=EMBER, spacing=1.35)

    # ── 3 · the product: four screens, four phones ───────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.55), "the product")
    y = heading(s, L, Inches(0.95), FULL, "A rep is a hit. A sloppy rep is a weak one.", size=34) + Inches(0.35)
    ph = Inches(4.3)
    pw = Emu(int(ph * FRAME["front"]["w"] / FRAME["front"]["h"]))
    gap = (FULL - 4 * pw) // 3
    for i, (img, cap) in enumerate([
        ("51-fight-landing.png", "The fight"),
        ("36-seeded-summary.png", "Every rep graded"),
        ("30-seeded-progress.png", "Form over time"),
        ("2f-rewards.png", "Rewards earned"),
    ]):
        device(s, a(img), L + i * (pw + gap), y, ph, cap)

    # ── 4 · the referee ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.7), "how it judges")
    ph = Inches(5.7)
    pw = Emu(int(ph * FRAME["front"]["w"] / FRAME["front"]["h"]))
    px = W - L - pw
    col = px - L - Inches(0.6)
    y = heading(s, L, Inches(1.1), col, "33 landmarks, every frame.", size=38) + Inches(0.4)
    for t, d in [
        ("MediaPipe PoseLandmarker", "33 body points, on-device, at camera rate"),
        ("Four measurements per rep", "depth · range of motion · tempo · alignment"),
        ("A fatigue estimate", "velocity and range loss against your own baseline"),
        ("Nothing is stored", "frames are read, scored and discarded in the same instant"),
    ]:
        card(s, L, y, col, Inches(0.92))
        text(s, L + Inches(0.35), y + Inches(0.15), col - Inches(0.6), Inches(0.34), t, size=15, bold=True)
        text(s, L + Inches(0.35), y + Inches(0.52), col - Inches(0.6), Inches(0.3), d, size=11, color=MUTED)
        y += Inches(1.06)
    device(s, a("a0-workout-midset.png"), px, Inches(0.95), ph, "Workout mode — the same referee, coaching")

    # ── 5 · on-device AI ──────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.7), "on-device ai")
    ph = Inches(5.8)
    pw = Emu(int(ph * FRAME["front"]["w"] / FRAME["front"]["h"]))
    px = W - L - pw
    col = px - L - Inches(0.6)
    y = heading(s, L, Inches(1.1), col, "A coach that only says what it measured.", size=38) + Inches(0.35)
    y = body(s, L, y, col,
             f"{FACTS['model']} runs inside the app — no network, no account, no request leaves the phone.\n\n"
             "It is handed a fact sheet built from your own measurements and told it may use nothing else. "
             "Ask it something the numbers do not cover and it says so.", size=15) + Inches(0.45)
    cw = (col - 2 * Inches(0.25)) // 3
    for i, (t, d, c) in enumerate([
        ("On this phone", "Gemma 3n, offline", SUCCESS),
        ("Cloud", "only if you opt in", EMBER),
        ("Built-in", "template bank, always", MUTED),
    ]):
        x = L + i * (cw + Inches(0.25))
        card(s, x, y, cw, Inches(1.15))
        text(s, x + Inches(0.2), y + Inches(0.2), cw - Inches(0.4), Inches(0.32), t, size=13, bold=True, color=c)
        text(s, x + Inches(0.2), y + Inches(0.6), cw - Inches(0.4), Inches(0.4), d, size=10, color=MUTED)
    body(s, L, y + Inches(1.5), col,
         "The badge on screen always names the voice that answered, because \"an AI said it\" "
         "and \"a lookup table said it\" are different claims.", size=13, color=EMBER, spacing=1.35)
    device(s, a("coach-chat.png"), px, Inches(0.9), ph, "Gemma, answering offline")

    # ── 6 · outdoors ──────────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.6), "beyond the room")
    ph = Inches(5.3)
    pw = Emu(int(ph * FRAME["front"]["w"] / FRAME["front"]["h"]))
    x2 = W - L - pw
    x1 = x2 - pw - Inches(0.4)
    col = x1 - L - Inches(0.6)
    y = heading(s, L, Inches(1.0), col, "Outdoors, the chase is the workout.", size=34) + Inches(0.35)
    body(s, L, y, col,
         "Zombie Run puts a pack on a real map behind you, and they close when your cadence drops — "
         "so stopping is what gets you caught.\n\n"
         "Runs and walks pass six quality gates, and fall back to your own footsteps indoors, "
         "with a stride learned from your outdoor GPS.", size=15)
    device(s, a("2e-zombie-run.png"), x1, Inches(1.3), ph, "Zombie Run")
    device(s, a("28-run.png"), x2, Inches(1.3), ph, "Outdoors")

    # ── 7 · video · zombie run ────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.5), "live")
    y = heading(s, L, Inches(0.85), FULL, "Zombie Run, on a real street.", size=36) + Inches(0.3)
    ph = Inches(5.0)
    pw = Emu(int(ph * FRAME["front"]["w"] / FRAME["front"]["h"]))
    gap = Inches(2.4)
    x1 = (W - 2 * pw - gap) // 2
    video_device(s, x1, y, ph, zombie[0] if zombie else None, "The head start, and the pack on the map")
    video_device(s, x1 + pw + gap, y, ph, zombie[1] if len(zombie) > 1 else None, "Running it, on a real street")

    # ── 8 · video · walking ───────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.5), "live")
    y = heading(s, L, Inches(0.85), FULL, "A walk, tracked and graded.", size=36) + Inches(0.3)
    ph = Inches(5.0)
    pw = video_device(s, L + Inches(0.4), y, ph, walk, "Distance, pace, route")
    tx = L + Inches(0.4) + pw + Inches(0.9)
    tw = W - L - tx
    yy = card_text(s, tx, y + Inches(0.1), tw,
                   "Six quality gates before a fix may move your distance:\n\n"
                   "accuracy under 25 m  ·  a 10-second settle window\n"
                   "sane coordinates  ·  monotonic timestamps\n"
                   "nothing faster than 8 m/s  ·  a 2 m jitter floor", size=14, color=INK)
    body(s, tx, yy + Inches(0.35), tw,
         "A rejected fix never becomes the anchor for the next one — which is what makes the "
         "jitter filter safe for a slow walk.", size=14, spacing=1.4)

    # ── 9 · technical depth ───────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.6), "technical depth")
    y = heading(s, L, Inches(0.95), FULL, "Built to be checked, not just demoed.", size=38) + Inches(0.2)
    sw = FULL // 5
    for i, (v, l) in enumerate([
        (FACTS["tests"], "tests, all green"), (FACTS["lines"], "lines of Kotlin"),
        (FACTS["screens"], "screens"), (FACTS["modes"], "game modes"), (FACTS["cold_start"], "cold start"),
    ]):
        yy = stat(s, L + i * sw, y, sw, v, l)
    y = body(s, L, yy, FULL,
             "Kotlin 2.3 · Jetpack Compose · Room · CameraX · MediaPipe Tasks (Vision + GenAI) · "
             "Filament 3D · osmdroid · Firebase Auth & Firestore", size=14, color=EMBER, spacing=1.3) + Inches(0.25)
    # One line of detail each, so three rows fit under two lines of stack. The longer versions
    # wrapped, and the third card ran off the slide.
    for t, d in [
        ("One engine, many modes",
         "The same counter, depth gate and form score run the fight, the gym log and the clinic test."),
        ("Pure core, testable on the JVM",
         "Scoring, fatigue, routes and rewards are Android-free: 942 tests run in seconds, no device."),
        ("Rendered screenshot baselines",
         "Every screen is rendered at 320 dp, 384 dp, tablet and 1.5x text before any phone sees it."),
    ]:
        y = row_card(s, L, y, FULL, t, d, title_w=Inches(3.4)) + Inches(0.1)

    # ── 10 · the phone, and the close ─────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, L, Inches(0.6), "what the iqoo 15 does")
    y = heading(s, L, Inches(0.95), FULL, "Everything that matters happens on the device.", size=36) + Inches(0.25)
    for t, d in [
        ("Camera", "MediaPipe pose and hand tracking at camera rate — the referee, and gesture control with no touch"),
        ("Neural engine", "Gemma 3n E2B int4 generating coaching text offline, in about a second"),
        ("GPU", "Filament renders the 3D boss while the pose pipeline runs"),
        ("Sensors", "step counter, compass and GPS fused into one position, indoors and out"),
        ("Voice", "offline speech recognition for commands, text-to-speech for the coach"),
    ]:
        y = row_card(s, L, y, FULL, t, d, title_w=Inches(2.4), title_color=EMBER) + Inches(0.08)
    body(s, L, y + Inches(0.15), FULL,
         "No frame, no landmark and no route ever leaves the phone. Only a score, a name and a level "
         "go to the leaderboard — and the app says so on its own privacy screen.", size=15, color=INK, spacing=1.35)

    out = os.path.join(ROOT, "ClashFit.pptx")
    prs.save(out)
    return out


def to_pdf(pptx):
    """The same deck as a PDF. Video slides carry their poster frame, since a PDF cannot play."""
    subprocess.run(["libreoffice", "--headless", "--convert-to", "pdf", "--outdir", ROOT, pptx],
                   capture_output=True, timeout=600)
    pdf = os.path.splitext(pptx)[0] + ".pdf"
    return pdf if os.path.exists(pdf) else None


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--zombie", nargs="*", default=[])
    ap.add_argument("--walk", default=None)
    ap.add_argument("--pdf", action="store_true")
    args = ap.parse_args()
    path = build(args.zombie, args.walk)
    print(f"wrote {path}")
    embedded = len([v for v in args.zombie if os.path.exists(v)]) + (1 if args.walk and os.path.exists(args.walk) else 0)
    print(f"videos embedded: {embedded} of 3")
    if args.pdf:
        pdf = to_pdf(path)
        print(f"wrote {pdf}" if pdf else "pdf export failed")
