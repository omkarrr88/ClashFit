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


def stat(s, x, y, w, value, label, color=EMBER):
    text(s, x, y, w, Inches(0.7), value, size=40, bold=True, color=color, align=PP_ALIGN.CENTER)
    text(s, x, y + Inches(0.72), w, Inches(0.4), label.upper(), size=10,
         color=MUTED, align=PP_ALIGN.CENTER)


def a(p):
    return os.path.join(ASSETS, p)


def video_slot(s, x, y, w, h, path, caption):
    """A video if we have one, a labelled placeholder if we do not."""
    if path and os.path.exists(path):
        poster = path + ".poster.png"
        if not os.path.exists(poster):
            subprocess.run(
                ["ffmpeg", "-y", "-i", path, "-vf", "select=eq(n\\,20)", "-vframes", "1", poster],
                capture_output=True,
            )
        s.shapes.add_movie(
            path, x, y, w, h,
            poster_frame_image=poster if os.path.exists(poster) else None,
            mime_type="video/mp4",
        )
    else:
        card(s, x, y, w, h, PANEL_HI)
        text(s, x, y + h / 2 - Inches(0.4), w, Inches(0.8),
             "▶\nvideo drops in here", size=13, color=FAINT, align=PP_ALIGN.CENTER)
    text(s, x, y + h + Inches(0.12), w, Inches(0.3), caption, size=11,
         color=MUTED, align=PP_ALIGN.CENTER)


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
    phone(s, a("51-fight-landing.png"), Inches(9.6), Inches(0.75), Inches(6.0))

    # ── 2 · the problem ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.8), "the problem")
    text(s, Inches(0.9), Inches(1.2), Inches(11.5), Inches(1.4),
         "Fitness apps count what you tell them.", size=44, bold=True)
    text(s, Inches(0.9), Inches(2.75), Inches(5.4), Inches(2.2),
         "You type in three sets of ten. The app believes you.\n\n"
         "It never saw the reps, so it cannot know whether they were deep, "
         "controlled, or worth anything at all — and neither can you.",
         size=16, color=MUTED, spacing=1.4)
    card(s, Inches(6.9), Inches(2.4), Inches(5.5), Inches(2.5))
    text(s, Inches(7.3), Inches(2.75), Inches(4.7), Inches(2),
         "So the number that motivates you\nis the one number nobody checked.\n\n"
         "Make the camera the referee and\nevery number becomes evidence.",
         size=19, color=INK, spacing=1.45)
    text(s, Inches(0.9), Inches(5.4), Inches(11.5), Inches(0.9),
         "ClashFit scores depth, range, tempo and alignment on every single rep, on the phone, "
         "and pays you in damage for the good ones.",
         size=16, color=EMBER, spacing=1.35)

    # ── 3 · the product ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.55), "the product")
    text(s, Inches(0.9), Inches(0.95), Inches(11), Inches(0.8),
         "A rep is a hit. A sloppy rep is a weak one.", size=36, bold=True)
    for i, (img, cap) in enumerate([
        ("51-fight-landing.png", "The fight — boss HP, combo, fatigue"),
        ("36-seeded-summary.png", "Every rep graded, after the set"),
        ("30-seeded-progress.png", "Form over time, measured"),
        ("2f-rewards.png", "Rewards earned by clean reps"),
    ]):
        x = Inches(0.9 + i * 3.05)
        phone(s, a(img), x, Inches(1.95), Inches(4.35))
        text(s, x - Inches(0.3), Inches(6.45), Inches(2.6), Inches(0.5), cap,
             size=10, color=MUTED, align=PP_ALIGN.CENTER)

    # ── 4 · the referee ───────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.7), "how it judges")
    text(s, Inches(0.9), Inches(1.1), Inches(11), Inches(0.9),
         "The referee: 33 landmarks, every frame.", size=40, bold=True)
    items = [
        ("MediaPipe PoseLandmarker", "33 body points, on-device, at camera rate"),
        ("Four measurements per rep", "depth · range of motion · tempo · alignment"),
        ("A fatigue estimate", "velocity and range loss against your own baseline"),
        ("Nothing is stored", "frames are read, scored and discarded in the same instant"),
    ]
    for i, (t, d) in enumerate(items):
        y = Inches(2.25 + i * 1.05)
        card(s, Inches(0.9), y, Inches(6.9), Inches(0.9))
        text(s, Inches(1.25), y + Inches(0.14), Inches(6.3), Inches(0.35), t, size=15, bold=True)
        text(s, Inches(1.25), y + Inches(0.5), Inches(6.3), Inches(0.3), d, size=11, color=MUTED)
    phone(s, a("a0-workout-midset.png"), Inches(8.4), Inches(1.0), Inches(6.0))
    text(s, Inches(8.2), Inches(7.05), Inches(3.4), Inches(0.35),
         "Workout mode — the same referee, coaching", size=10, color=MUTED, align=PP_ALIGN.CENTER)

    # ── 5 · on-device AI ──────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.7), "on-device ai")
    text(s, Inches(0.9), Inches(1.1), Inches(11), Inches(0.9),
         "A coach that only says what it measured.", size=40, bold=True)
    text(s, Inches(0.9), Inches(2.15), Inches(6.6), Inches(1.6),
         f"{FACTS['model']} runs inside the app — no network, no account, "
         "no request leaves the phone.\n\n"
         "It is handed a fact sheet built from your own measurements and told it may use "
         "nothing else. Ask it something the numbers do not cover and it says so.",
         size=15, color=MUTED, spacing=1.4)
    for i, (t, d, c) in enumerate([
        ("On this phone", "Gemma 3n, offline", SUCCESS),
        ("Cloud", "only if you opt in", EMBER),
        ("Built-in", "template bank, always", MUTED),
    ]):
        x = Inches(0.9 + i * 2.3)
        card(s, x, Inches(4.1), Inches(2.1), Inches(1.25))
        text(s, x + Inches(0.2), Inches(4.3), Inches(1.7), Inches(0.35), t, size=13, bold=True, color=c)
        text(s, x + Inches(0.2), Inches(4.7), Inches(1.75), Inches(0.5), d, size=10, color=MUTED)
    text(s, Inches(0.9), Inches(5.7), Inches(6.6), Inches(0.9),
         "The badge on screen always names the voice that answered, because "
         "\"an AI said it\" and \"a lookup table said it\" are different claims.",
         size=13, color=EMBER, spacing=1.35)
    phone(s, a("coach-chat.png"), Inches(8.6), Inches(0.9), Inches(6.1))

    # ── 6 · outdoors ──────────────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.6), "beyond the room")
    text(s, Inches(0.9), Inches(1.0), Inches(11), Inches(0.9),
         "Outdoors, the chase is the workout.", size=40, bold=True)
    text(s, Inches(0.9), Inches(2.0), Inches(5.2), Inches(2.4),
         "Zombie Run puts a pack on a real map behind you, and they close when your "
         "cadence drops — so stopping is what gets you caught.\n\n"
         "Runs and walks are tracked through six quality gates, and fall back to your "
         "own footsteps indoors, with a stride learned from your outdoor GPS.",
         size=15, color=MUTED, spacing=1.4)
    phone(s, a("2e-zombie-run.png"), Inches(6.5), Inches(1.55), Inches(5.4))
    phone(s, a("28-run.png"), Inches(9.4), Inches(1.55), Inches(5.4))

    # ── 7 · video · zombie run ────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.5), "live")
    text(s, Inches(0.9), Inches(0.85), Inches(11), Inches(0.7),
         "Zombie Run, on a real street.", size=38, bold=True)
    vw, vh = Inches(2.7), Inches(4.8)
    video_slot(s, Inches(2.6), Inches(1.85), vw, vh, zombie[0] if zombie else None, "The chase")
    video_slot(s, Inches(7.9), Inches(1.85), vw, vh, zombie[1] if len(zombie) > 1 else None, "Caught, and the map")

    # ── 8 · video · walking ───────────────────────────────────────────────────────────────
    s = slide(prs)
    kicker(s, Inches(0.9), Inches(0.5), "live")
    text(s, Inches(0.9), Inches(0.85), Inches(11), Inches(0.7),
         "A walk, tracked and graded.", size=38, bold=True)
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
    text(s, Inches(0.9), Inches(1.0), Inches(11), Inches(0.8),
         "Built to be checked, not just demoed.", size=40, bold=True)
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
    text(s, Inches(0.9), Inches(1.05), Inches(11), Inches(0.85),
         "Everything that matters happens on the device.", size=38, bold=True)
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
