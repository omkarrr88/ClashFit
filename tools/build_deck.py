#!/usr/bin/env python3
"""
Builds the ClashFit pitch deck.

Ten slides, 16:9, in the app's own palette, so the deck and the thing on the phone
look like one product rather than two.

Every number on a slide is read from this file's FACTS block, and every one of those
was verified against the codebase rather than remembered. If a number changes, change
it here and re-run; nothing is typed twice.

Videos are optional. Pass them and they are embedded; leave them out and the slide
renders a labelled placeholder so the layout is already correct when they arrive.

    python3 tools/build_deck.py [--zombie A.mp4 B.mp4] [--walk C.mp4]
"""

import argparse
import os
import subprocess
import sys
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "deck-assets")

# ── a light deck, carrying the app's accent ───────────────────────────────────────────────
#
# The product is dark and the deck is not, deliberately: these are shown on a projector in a lit
# hall, where a dark slide turns into a grey rectangle and a room full of people squint at it. The
# ember stays, because that is the thing the app and the deck actually share, and the screenshots
# keep their own dark chrome — on paper white they read as a phone held up rather than as a hole.
GROUND = RGBColor(0xFA, 0xF9, 0xF7)      # warm paper, not clinical white
PANEL = RGBColor(0xF0, 0xEE, 0xEA)       # a card on the page
PANEL_HI = RGBColor(0xE4, 0xE1, 0xDB)    # a card on a card
EMBER = RGBColor(0xE0, 0x44, 0x18)       # the brand, darkened for 4.9:1 on paper
INK = RGBColor(0x14, 0x16, 0x1A)
MUTED = RGBColor(0x51, 0x56, 0x5D)       # 7.9:1 on paper
# 4.6:1 clears the standard, and the standard is written for text you read at arm's length. This
# is a projector at the back of a hall, so the only thing left at this weight is the placeholder
# on a slide that will be replaced by video.
FAINT = RGBColor(0x6E, 0x73, 0x7A)       # 5.8:1 on paper
SUCCESS = RGBColor(0x1F, 0x7A, 0x45)     # darkened for contrast on paper

W, H = Inches(13.333), Inches(7.5)

# ── every figure on a slide, verified against the codebase ────────────────────────────────
FACTS = {
    "tests": "942",
    "kt_files": "228",
    "lines": "41,268",
    "screens": "29",
    "modes": "18",
    "measured_exercises": "4",
    "achievements": "22",
    "vouchers": "10",
    "model": "Gemma 3n E2B · int4 · 3.1 GB",
    "cold_start": "494 ms",
}


def slide(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    bg = s.shapes.add_shape(1, 0, 0, W, H)
    bg.fill.solid()
    bg.fill.fore_color.rgb = GROUND
    bg.line.fill.background()
    bg.shadow.inherit = False
    return s


def text(s, x, y, w, h, body, size=18, color=INK, bold=False, align=PP_ALIGN.LEFT,
         font="Verdana", spacing=1.15):
    tb = s.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    lines = body.split("\n")
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = spacing
        r = p.add_run()
        r.text = line
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = font
    return tb


def kicker(s, x, y, label):
    text(s, x, y, Inches(6), Inches(0.3), label.upper(), size=11, color=EMBER, bold=True)


def card(s, x, y, w, h, fill=PANEL):
    sh = s.shapes.add_shape(5, x, y, w, h)  # rounded rectangle
    sh.fill.solid()
    sh.fill.fore_color.rgb = fill
    # A tinted card on paper is nearly invisible without an edge; on the dark deck the fill alone
    # was enough.
    sh.line.color.rgb = RGBColor(0xDD, 0xD9, 0xD2)
    sh.line.width = Pt(1)
    sh.shadow.inherit = False
    sh.adjustments[0] = 0.06
    return sh


def phone(s, img, x, y, height):
    """A screenshot at its own aspect ratio, so nothing is stretched."""
    from PIL import Image
    with Image.open(img) as im:
        ratio = im.size[0] / im.size[1]
    w = Emu(int(height * ratio))
    pic = s.shapes.add_picture(img, x, y, width=w, height=height)
    pic.line.color.rgb = RGBColor(0xC8, 0xC4, 0xBD)
    pic.line.width = Pt(0.75)
    return w


def device(s, img, x, y, height, label=None):
    """
    A screenshot inside a phone, rather than floating on the page.

    The landing page frames every screen this way and it is the single cheapest thing that makes
    a screenshot read as a product rather than as a picture of one — on a light deck especially,
    where an unframed dark rectangle looks like a hole. The body is drawn rather than composited
    so it costs nothing and scales with the slide.
    """
    from PIL import Image
    with Image.open(img) as im:
        ratio = im.size[0] / im.size[1]
    w = Emu(int(height * ratio))
    bez = Emu(int(height * 0.022))          # the bezel around the glass
    body_w, body_h = Emu(w + 2 * bez), Emu(height + 2 * bez)

    shell = s.shapes.add_shape(5, Emu(x - bez), Emu(y - bez), body_w, body_h)
    shell.fill.solid()
    # A white body, not a black one. On a light deck a dark bezel reads as a heavy border drawn
    # around the screen; a white one disappears into the page and leaves the dark app screen as
    # the only thing with weight on it, which is the thing worth looking at.
    shell.fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    shell.line.color.rgb = RGBColor(0xC9, 0xC6, 0xC0)
    shell.line.width = Pt(1.25)
    shell.shadow.inherit = False
    shell.adjustments[0] = 0.055

    pic = s.shapes.add_picture(img, x, y, width=w, height=height)
    pic.line.fill.background()

    if label:
        text(s, Emu(x - bez), Emu(y + height + bez) + Inches(0.14), body_w, Inches(0.3),
             label, size=10, color=MUTED, align=PP_ALIGN.CENTER)
    return Emu(w + 2 * bez)


def stat(s, x, y, w, value, label, color=EMBER):
    text(s, x, y, w, Inches(0.7), value, size=40, bold=True, color=color, align=PP_ALIGN.CENTER)
    text(s, x, y + Inches(0.72), w, Inches(0.4), label.upper(), size=10,
         color=MUTED, align=PP_ALIGN.CENTER)


def a(p):
    return os.path.join(ASSETS, p)


def _ffmpeg():
    """
    Wherever ffmpeg happens to be.

    Without a poster frame PowerPoint draws a grey rectangle where the video is, which on a light
    deck looks like a broken image rather than something to press play on. The system has no
    ffmpeg and pip refuses to install into it, so a throwaway virtualenv beside the project
    carries a static one.
    """
    local = os.path.join(ROOT, ".venv-deck", "bin", "python")
    if os.path.exists(local):
        out = subprocess.run(
            [local, "-c", "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())"],
            capture_output=True, text=True,
        )
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    return "ffmpeg"


def video_slot(s, x, y, w, h, path, caption):
    """A video if we have one, a labelled placeholder if we do not."""
    if path and os.path.exists(path):
        # The poster is what the slide shows until somebody presses play, so it is worth having.
        # ffmpeg gives a real frame from the clip; without it PowerPoint falls back to a grey
        # rectangle, which on a light deck looks like a broken image rather than a video.
        poster = os.path.join(ASSETS, os.path.basename(path) + ".poster.jpg")
        if not os.path.exists(poster):
            try:
                subprocess.run(
                    [_ffmpeg(), "-y", "-ss", "2", "-i", path, "-frames:v", "1",
                     "-q:v", "3", poster],
                    capture_output=True, check=True,
                )
            except (OSError, subprocess.CalledProcessError, RuntimeError):
                poster = None
        s.shapes.add_movie(
            path, x, y, w, h,
            poster_frame_image=poster if poster and os.path.exists(poster) else None,
            mime_type="video/mp4",
        )
    else:
        card(s, x, y, w, h, PANEL_HI)
        text(s, x, y + h / 2 - Inches(0.4), w, Inches(0.8),
             "▶\nvideo drops in here", size=13, color=FAINT, align=PP_ALIGN.CENTER)
    text(s, x, y + h + Inches(0.12), w, Inches(0.3), caption, size=11,
         color=MUTED, align=PP_ALIGN.CENTER)



def _lines(body, size_pt, width_in, tight=False):
    """
    How many lines this text will take at this size in this width.

    python-pptx cannot measure text and PowerPoint will happily let a box overflow onto whatever
    is beneath it, which is exactly what went wrong: every heading longer than its box wrapped
    silently and its second line landed under the next element. This estimates the wrap so the
    layout can reserve the right height instead of guessing one.

    The factor is the average advance width of this font as a fraction of point size — narrower
    for the bold display sizes, wider for body copy. Rounded up, and never less than the explicit
    line breaks the caller wrote.
    """
    import math
    # Measured off a render rather than guessed: at 38pt in 7.1in the bold face fits about 20
    # characters, which puts its average advance at 0.67 of point size. The first guess of 0.48
    # was optimistic enough to predict one line where two appeared, and the second line landed
    # underneath the next element.
    factor = 0.68 if tight else 0.52
    per_line = max(1, int((width_in * 72.0) / (size_pt * factor)))
    total = 0
    for para in body.split("\n"):
        total += max(1, math.ceil(len(para) / per_line))
    return total


def heading(s, x, y, w, copy, size=40):
    """A heading that reserves the height it will actually occupy, and says where it ends."""
    n = _lines(copy, size, w / 914400, tight=True)
    h = Inches(n * size * 1.16 / 72.0)
    text(s, x, y, w, h, copy, size=size, bold=True, spacing=1.16)
    return y + h


def body(s, x, y, w, copy, size=16, color=None, spacing=1.4):
    """Body copy that reserves its height too, and says where it ends."""
    c = MUTED if color is None else color
    n = _lines(copy, size, w / 914400)
    h = Inches(n * size * spacing / 72.0)
    text(s, x, y, w, h, copy, size=size, color=c, spacing=spacing)
    return y + h


def build(zombie, walk):
    prs = Presentation()
    prs.slide_width, prs.slide_height = W, H

    # ── 1 · title ─────────────────────────────────────────────────────────────────────────
    s = slide(prs)
    text(s, Inches(0.9), Inches(2.1), Inches(8), Inches(1.6), "CLASHFIT",
         size=76, bold=True, color=INK)
    text(s, Inches(0.95), Inches(3.5), Inches(8.4), Inches(1.2),
         "Your body is the controller.\nYour camera is the referee.",
         size=26, color=EMBER, spacing=1.25)
    text(s, Inches(0.95), Inches(5.2), Inches(8), Inches(0.9),
         "A fitness game where a clean rep does more damage than a sloppy one —\n"
         "because the phone measured the difference.",
         size=14, color=MUTED, spacing=1.35)
    text(s, Inches(0.95), Inches(6.5), Inches(6), Inches(0.4),
         "Team Da Goats  ·  Omkar Kadam · Ujjwal Pardeshi  ·  iQOO Hackathon 2026",
         size=12, color=MUTED)
    device(s, a("51-fight-landing.png"), Inches(9.6), Inches(0.8), Inches(5.9), "iQOO 15")

    # ── 2 · the problem ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.8), "the problem")
    y = heading(s, Inches(0.9), Inches(1.2), Inches(11.5),
                "Fitness apps count what you tell them.", size=42)
    y += Inches(0.35)
    col = Inches(5.4)
    body(s, Inches(0.9), y, col,
         "You type in three sets of ten. The app believes you.\n\n"
         "It never saw the reps, so it cannot know whether they were deep, controlled, "
         "or worth anything at all — and neither can you.")
    card(s, Inches(6.9), y - Inches(0.15), Inches(5.5), Inches(2.45))
    text(s, Inches(7.3), y + Inches(0.2), Inches(4.7), Inches(1.9),
         "So the number that motivates you\nis the one number nobody checked.\n\n"
         "Make the camera the referee and\nevery number becomes evidence.",
         size=17, color=INK, spacing=1.4)
    body(s, Inches(0.9), Inches(5.6), Inches(11.5),
         "ClashFit scores depth, range, tempo and alignment on every single rep, on the phone, "
         "and pays you in damage for the good ones.",
         size=16, color=EMBER, spacing=1.35)

    # ── 3 · the product ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.55), "the product")
    heading(s, Inches(0.9), Inches(0.95), Inches(11.5), "A rep is a hit. A sloppy rep is a weak one.", size=34)
    for i, (img, cap) in enumerate([
        ("51-fight-landing.png", "The fight — boss HP, combo, fatigue"),
        ("36-seeded-summary.png", "Every rep graded, after the set"),
        ("30-seeded-progress.png", "Form over time, measured"),
        ("2f-rewards.png", "Rewards earned by clean reps"),
    ]):
        x = Inches(0.95 + i * 3.05)
        device(s, a(img), x, Inches(2.05), Inches(4.2), cap)

    # ── 4 · the referee ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.7), "how it judges")
    # 7.1in, not 11: the phone occupies the right of this slide, and a heading that ran the full
    # width wrapped underneath it and put its second line beneath the first card.
    y = heading(s, Inches(0.9), Inches(1.1), Inches(7.1), "33 landmarks, every frame.", size=38)
    y += Inches(0.4)
    items = [
        ("MediaPipe PoseLandmarker", "33 body points, on-device, at camera rate"),
        ("Four measurements per rep", "depth · range of motion · tempo · alignment"),
        ("A fatigue estimate", "velocity and range loss against your own baseline"),
        ("Nothing is stored", "frames are read, scored and discarded in the same instant"),
    ]
    for t, d in items:
        card(s, Inches(0.9), y, Inches(6.9), Inches(0.92))
        text(s, Inches(1.25), y + Inches(0.15), Inches(6.3), Inches(0.34), t, size=15, bold=True)
        text(s, Inches(1.25), y + Inches(0.52), Inches(6.3), Inches(0.3), d, size=11, color=MUTED)
        y += Inches(1.06)
    device(s, a("a0-workout-midset.png"), Inches(8.6), Inches(1.05), Inches(5.7),
           "Workout mode — the same referee, coaching")

    # ── 5 · on-device AI ──────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.7), "on-device ai")
    y = heading(s, Inches(0.9), Inches(1.1), Inches(7.2), "A coach that only says\nwhat it measured.", size=38)
    y += Inches(0.35)
    y = body(s, Inches(0.9), y, Inches(6.6),
             f"{FACTS['model']} runs inside the app — no network, no account, no request "
             "leaves the phone.\n\n"
             "It is handed a fact sheet built from your own measurements and told it may use "
             "nothing else. Ask it something the numbers do not cover and it says so.",
             size=15)
    y += Inches(0.5)
    for i, (t, d, c) in enumerate([
        ("On this phone", "Gemma 3n, offline", SUCCESS),
        ("Cloud", "only if you opt in", EMBER),
        ("Built-in", "template bank, always", MUTED),
    ]):
        x = Inches(0.9 + i * 2.3)
        card(s, x, y, Inches(2.1), Inches(1.2))
        text(s, x + Inches(0.2), y + Inches(0.2), Inches(1.75), Inches(0.32), t, size=13, bold=True, color=c)
        text(s, x + Inches(0.2), y + Inches(0.6), Inches(1.75), Inches(0.45), d, size=10, color=MUTED)
    body(s, Inches(0.9), y + Inches(1.5), Inches(6.6),
         "The badge on screen always names the voice that answered, because \"an AI said it\" "
         "and \"a lookup table said it\" are different claims.",
         size=13, color=EMBER, spacing=1.35)
    device(s, a("coach-chat.png"), Inches(8.7), Inches(0.95), Inches(5.8), "Gemma, answering offline")

    # ── 6 · outdoors ──────────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.6), "beyond the room")
    heading(s, Inches(0.9), Inches(1.0), Inches(6.0), "Outdoors, the chase is the workout.", size=36)
    text(s, Inches(0.9), Inches(2.35), Inches(5.2), Inches(2.4),
         "Zombie Run puts a pack on a real map behind you, and they close when your "
         "cadence drops — so stopping is what gets you caught.\n\n"
         "Runs and walks are tracked through six quality gates, and fall back to your "
         "own footsteps indoors, with a stride learned from your outdoor GPS.",
         size=15, color=MUTED, spacing=1.4)
    device(s, a("2e-zombie-run.png"), Inches(6.6), Inches(1.6), Inches(5.2), "Zombie Run")
    device(s, a("28-run.png"), Inches(9.6), Inches(1.6), Inches(5.2), "Outdoors")

    # ── 7 · video · zombie run ────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.5), "live")
    heading(s, Inches(0.9), Inches(0.85), Inches(11.5), "Zombie Run, on a real street.", size=36)
    vw, vh = Inches(2.7), Inches(4.8)
    video_slot(s, Inches(2.6), Inches(1.85), vw, vh, zombie[0] if zombie else None,
               "The head start, and the pack on the map")
    video_slot(s, Inches(7.9), Inches(1.85), vw, vh, zombie[1] if len(zombie) > 1 else None,
               "Running it, on a real street")

    # ── 8 · video · walking ───────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.5), "live")
    heading(s, Inches(0.9), Inches(0.85), Inches(11.5), "A walk, tracked and graded.", size=36)
    video_slot(s, Inches(1.1), Inches(1.85), Inches(2.7), Inches(4.8), walk, "Distance, pace, route")
    text(s, Inches(4.6), Inches(2.2), Inches(7.8), Inches(3),
         "Six quality gates before a fix may move your distance:\n\n"
         "accuracy under 25 m   ·   a 10-second settle window\n"
         "sane coordinates   ·   monotonic timestamps\n"
         "nothing faster than 8 m/s   ·   a 2 m jitter floor\n\n"
         "A rejected fix never becomes the anchor for the next one — which is what "
         "makes the jitter filter safe for a slow walk.",
         size=15, color=MUTED, spacing=1.5)

    # ── 9 · technical depth ───────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.6), "technical depth")
    heading(s, Inches(0.9), Inches(1.0), Inches(11.5), "Built to be checked, not just demoed.", size=38)
    for i, (v, l) in enumerate([
        (FACTS["tests"], "tests, all green"),
        (FACTS["lines"], "lines of Kotlin"),
        (FACTS["screens"], "screens"),
        (FACTS["modes"], "game modes"),
        (FACTS["cold_start"], "cold start"),
    ]):
        stat(s, Inches(0.9 + i * 2.42), Inches(2.15), Inches(2.2), v, l)
    text(s, Inches(0.9), Inches(3.75), Inches(11.5), Inches(0.5),
         "Kotlin 2.3 · Jetpack Compose · Room · CameraX · MediaPipe Tasks (Vision + GenAI) · "
         "Filament 3D · osmdroid · Firebase Auth & Firestore",
         size=14, color=EMBER, spacing=1.3)
    for i, (t, d) in enumerate([
        ("One engine, many modes",
         "The same rep counter, depth gate and form score drive a boss fight, a gym log and a clinical sit-to-stand test. Two things that must agree are one thing."),
        ("Pure core, testable on the JVM",
         "Scoring, fatigue, route maths and reward rules are Android-free, so 942 tests run in seconds without a device."),
        ("Rendered screenshot baselines",
         "Every screen is drawn at 320 dp, 384 dp, tablet and 1.5x text, so a broken layout is caught before a phone sees it."),
    ]):
        y = Inches(4.45 + i * 0.95)
        card(s, Inches(0.9), y, Inches(11.5), Inches(0.82))
        text(s, Inches(1.25), y + Inches(0.11), Inches(3.1), Inches(0.4), t, size=13, bold=True)
        text(s, Inches(4.5), y + Inches(0.14), Inches(7.6), Inches(0.6), d, size=11, color=MUTED, spacing=1.2)

    # ── 10 · the phone, and the close ─────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.65), "what the iqoo 15 does")
    heading(s, Inches(0.9), Inches(1.05), Inches(11.5), "Everything that matters happens on the device.", size=36)
    for i, (t, d) in enumerate([
        ("Camera", "MediaPipe pose and hand tracking at camera rate — the referee, and gesture control without touching the screen"),
        ("Neural engine", "Gemma 3n E2B int4 generating coaching text offline, in about a second"),
        ("GPU", "Filament renders the 3D boss while the pose pipeline runs"),
        ("Sensors", "step counter, compass and GPS fused into one position, indoors and out"),
        ("Voice", "offline speech recognition for commands, text-to-speech for the coach"),
    ]):
        y = Inches(2.1 + i * 0.82)
        card(s, Inches(0.9), y, Inches(11.5), Inches(0.7))
        text(s, Inches(1.25), y + Inches(0.16), Inches(2.2), Inches(0.4), t, size=13, bold=True, color=EMBER)
        text(s, Inches(3.5), y + Inches(0.17), Inches(8.6), Inches(0.4), d, size=11.5, color=MUTED)
    text(s, Inches(0.9), Inches(6.35), Inches(11.5), Inches(0.9),
         "No frame, no landmark and no route ever leaves the phone. Only a score, a name and a level "
         "go to the leaderboard — and the app says so on its own privacy screen.",
         size=15, color=INK, spacing=1.35)

    out = os.path.join(ROOT, "ClashFit.pptx")
    prs.save(out)
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--zombie", nargs="*", default=[])
    ap.add_argument("--walk", default=None)
    args = ap.parse_args()
    path = build(args.zombie, args.walk)
    print(f"wrote {path}")
    embedded = len([v for v in args.zombie if os.path.exists(v)]) + (
        1 if args.walk and os.path.exists(args.walk) else 0
    )
    print(f"videos embedded: {embedded} of 3")
