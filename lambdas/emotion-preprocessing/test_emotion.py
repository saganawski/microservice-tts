#!/usr/bin/env python3
"""
Standalone test script for the emotion tagger.

Usage:
    # Uses GEMINI_API_KEY env var
    python test_emotion.py

    # Override level
    EMOTION_LEVEL=dramatic python test_emotion.py

    # Tag a custom file
    python test_emotion.py --file /path/to/text.txt --level subtle
"""

import argparse
import os
import re
import sys
import textwrap

# Allow importing from this directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from emotion_tagger import EmotionTagger

# ─── Sample passages for testing ───

DRAMATIC_SCENE = textwrap.dedent("""\
    The door burst open with a crack that split the night air. Marcus stumbled \
    backward, his hand clutching the wound at his side. Blood seeped between \
    his fingers, warm and insistent.

    "You can't run forever," Elena said, stepping through the doorway. Her \
    voice was steady, but her hands trembled around the grip of the pistol.

    "I wasn't running." He laughed — a wet, broken sound that turned into a \
    cough. "I was waiting for you."

    She froze. Something in his eyes — not fear, not anger, but something \
    worse. Resignation.

    "Don't," she whispered. "Don't you dare give up on me now."

    Outside, thunder rolled across the valley like the world itself was \
    groaning. Rain hammered against the windows. Somewhere in the distance, \
    sirens wailed.

    Marcus sank to his knees. "I'm sorry, Elena. For everything."

    "No!" She rushed forward, catching him before he hit the floor. "Stay \
    with me. Stay with me, damn it!"

    His eyes were already glazing over, but he managed one last smile — \
    tired and impossibly gentle. "Tell the kids... tell them their old man \
    wasn't all bad."
""")

QUIET_SCENE = textwrap.dedent("""\
    The morning light filtered through the curtains, painting golden \
    rectangles on the hardwood floor. Sarah sat at the kitchen table with \
    her hands wrapped around a mug of tea that had long since gone cold.

    She watched a sparrow hop along the windowsill, pecking at crumbs \
    she'd left there yesterday. Such a small, ordinary thing. She wondered \
    when ordinary things had started to feel like miracles.

    The letter sat unopened beside her plate. She knew what it said — \
    or thought she did. The university seal on the envelope was enough. \
    Thirty years of teaching, and now this.

    She picked it up, turned it over, set it down again.

    "Not yet," she murmured to no one. "Let me have this morning first."

    The sparrow cocked its head, regarded her with one bright eye, and \
    flew away. Sarah smiled and took a sip of her cold tea. It tasted \
    like patience.
""")

DIALOGUE_HEAVY = textwrap.dedent("""\
    "You told her?" Jake slammed his fist on the table. "After everything \
    we agreed on?"

    "She deserved to know," Maria replied quietly. She didn't flinch.

    "Deserved? She's twelve years old!"

    "Old enough to wonder why her father never comes home."

    Jake opened his mouth, closed it. He turned away, pressing his \
    forehead against the cool glass of the window. The city lights \
    blurred through the rain.

    "I was going to tell her myself," he said, barely above a whisper. \
    "When the time was right."

    Maria sighed. "The time was right three years ago, Jake."

    Silence stretched between them like a wire pulled taut. Then, \
    unexpectedly, Jake laughed — short and humorless.

    "You know what the worst part is? She probably already figured it out. \
    Kids always do."

    "Yeah," Maria said softly. "They do."
""")

SAMPLE_PASSAGES = {
    "dramatic": ("Dramatic Action Scene", DRAMATIC_SCENE),
    "quiet": ("Quiet Morning Scene", QUIET_SCENE),
    "dialogue": ("Dialogue-Heavy Scene", DIALOGUE_HEAVY),
}


_CLOSING_TAG_RE = re.compile(r"</(?:laugh|sigh|excited|sad|whisper|yell|gasp|groan)>")


def run_test(tagger: EmotionTagger, name: str, text: str):
    """Tag a passage and print results."""
    print(f"\n{'=' * 70}")
    print(f"  {name}  |  Level: {tagger.level}")
    print(f"{'=' * 70}\n")

    tagged = tagger.tag(text)
    counts = tagger.tag_count(tagged)
    total = sum(counts.values())

    # Verify no closing tags survived
    closing_found = _CLOSING_TAG_RE.findall(tagged)
    if closing_found:
        print(f"  ** FAIL: closing tags found in output: {closing_found}")
    else:
        print(f"  PASS: no closing tags in output")

    print(tagged)
    print(f"\n{'─' * 70}")
    print(f"Tags inserted: {total}")
    for tag, count in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"  {tag}: {count}")
    print()


def main():
    parser = argparse.ArgumentParser(description="Test Orpheus emotion tagging")
    parser.add_argument("--level", choices=["none", "subtle", "dramatic"],
                        default=os.environ.get("EMOTION_LEVEL", "subtle"))
    parser.add_argument("--file", help="Path to a text file to tag")
    parser.add_argument("--scene", choices=list(SAMPLE_PASSAGES.keys()),
                        help="Run a specific sample scene (default: all)")
    parser.add_argument("--api-key", help="Gemini API key (default: GEMINI_API_KEY env)")
    args = parser.parse_args()

    api_key = args.api_key or os.environ.get("GEMINI_API_KEY")
    if not api_key and args.level != "none":
        print("Error: Set GEMINI_API_KEY or pass --api-key", file=sys.stderr)
        sys.exit(1)

    tagger = EmotionTagger(api_key=api_key, level=args.level)
    print(f"Emotion Tagger initialized — level: {tagger.level}")

    if args.file:
        with open(args.file) as f:
            text = f.read()
        run_test(tagger, f"Custom file: {args.file}", text)
    elif args.scene:
        name, text = SAMPLE_PASSAGES[args.scene]
        run_test(tagger, name, text)
    else:
        for key, (name, text) in SAMPLE_PASSAGES.items():
            run_test(tagger, name, text)


if __name__ == "__main__":
    main()
