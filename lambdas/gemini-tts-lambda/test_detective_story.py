#!/usr/bin/env python3
"""
Test script for Gemini TTS with detective story sample
Tests voice affect, pacing, and suspenseful narration
"""

import json
import os
from handler import lambda_handler

# Voice instructions based on your specifications
VOICE_INSTRUCTIONS = """Voice Affect: Low, hushed, and suspenseful; convey tension and intrigue.

Tone: Deeply serious and mysterious, maintaining an undercurrent of unease throughout.

Pacing: Slow, deliberate, pausing slightly after suspenseful moments to heighten drama.

Emotion: Restrained yet intense—voice should subtly tremble or tighten at key suspenseful points.

Emphasis: Highlight sensory descriptions ("footsteps echoed," "heart hammering," "shadows melting into darkness") to amplify atmosphere.

Pronunciation: Slightly elongated vowels and softened consonants for an eerie, haunting effect.

Pauses: Insert meaningful pauses after phrases like "only shadows melting into darkness," and especially before the final line, to enhance suspense dramatically."""

# Sample detective story text
DETECTIVE_STORY = """The night was thick with fog, wrapping the town in mist. Detective Evelyn Harper pulled her coat tighter, feeling the chill creep down her spine. She knew the town's buried secrets were rising again.

Footsteps echoed behind her, slow and deliberate. She turned, heart racing, but saw only shadows.

Evelyn steadied her breath—tonight felt different. Tonight, the danger felt personal. Somewhere nearby, hidden eyes watched her every move. Waiting. Planning. Knowing her next step.

This was just the beginning."""


def test_flash_model():
    """Test with Flash model (cost-effective)"""
    print("=" * 60)
    print("Testing Gemini 2.5 Flash TTS")
    print("=" * 60)

    event = {
        "text": DETECTIVE_STORY,
        "voiceName": "Alnilam",
        "voiceInstructions": VOICE_INSTRUCTIONS,
        "model": "gemini-2.5-flash-preview-tts",
        "bucket": None,  # Set to your S3 bucket to upload
        "key": None      # Set to S3 key path
    }

    result = lambda_handler(event, None)

    if result['statusCode'] == 200:
        body = json.loads(result['body'])
        print(f"\n✅ Success!")
        print(f"   Model: {body['model']}")
        print(f"   Voice: {body['voice']}")
        print(f"   Audio Size: {body['audioSizeBytes']:,} bytes")

        # Save audio to file if returned as base64
        if 'audioBase64' in body:
            import base64
            audio_data = base64.b64decode(body['audioBase64'])
            output_file = "detective_story_flash.wav"
            with open(output_file, 'wb') as f:
                f.write(audio_data)
            print(f"   Saved to: {output_file}")
    else:
        print(f"\n❌ Error: {result['body']}")

    print()


def test_pro_model():
    """Test with Pro model (premium quality)"""
    print("=" * 60)
    print("Testing Gemini 2.5 Pro TTS")
    print("=" * 60)

    event = {
        "text": DETECTIVE_STORY,
        "voiceName": "Alnilam",
        "voiceInstructions": VOICE_INSTRUCTIONS,
        "model": "gemini-2.5-pro-preview-tts",
        "bucket": None,
        "key": None
    }

    result = lambda_handler(event, None)

    if result['statusCode'] == 200:
        body = json.loads(result['body'])
        print(f"\n✅ Success!")
        print(f"   Model: {body['model']}")
        print(f"   Voice: {body['voice']}")
        print(f"   Audio Size: {body['audioSizeBytes']:,} bytes")

        if 'audioBase64' in body:
            import base64
            audio_data = base64.b64decode(body['audioBase64'])
            output_file = "detective_story_pro.wav"
            with open(output_file, 'wb') as f:
                f.write(audio_data)
            print(f"   Saved to: {output_file}")
    else:
        print(f"\n❌ Error: {result['body']}")

    print()


def test_simple_hello_world():
    """Simple hello world test"""
    print("=" * 60)
    print("Testing Simple Hello World")
    print("=" * 60)

    event = {
        "text": "Hello world! This is a test of the Gemini TTS system.",
        "voiceName": "Charon"
    }

    result = lambda_handler(event, None)

    if result['statusCode'] == 200:
        body = json.loads(result['body'])
        print(f"\n✅ Success!")
        print(f"   Model: {body['model']}")
        print(f"   Voice: {body['voice']}")
        print(f"   Audio Size: {body['audioSizeBytes']:,} bytes")

        if 'audioBase64' in body:
            import base64
            audio_data = base64.b64decode(body['audioBase64'])
            output_file = "hello_world.wav"
            with open(output_file, 'wb') as f:
                f.write(audio_data)
            print(f"   Saved to: {output_file}")
    else:
        print(f"\n❌ Error: {result['body']}")

    print()


if __name__ == "__main__":
    # Check for API key
    if not os.environ.get('GEMINI_API_KEY'):
        print("❌ Error: GEMINI_API_KEY environment variable not set")
        print("   Please export GEMINI_API_KEY=your-api-key")
        exit(1)

    print("\n🎙️  Gemini TTS Test Suite\n")

    # Run tests
    test_simple_hello_world()
    test_flash_model()

    # Uncomment to test Pro model (costs more)
    # test_pro_model()

    print("🎉 All tests complete!")
    print("\nGenerated audio files:")
    print("  - hello_world.wav (simple test)")
    print("  - detective_story_flash.wav (detective story with Flash model)")
    print("  # - detective_story_pro.wav (uncomment to test Pro model)\n")
