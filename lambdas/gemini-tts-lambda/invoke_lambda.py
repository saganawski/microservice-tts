#!/usr/bin/env python3
"""
Invoke the deployed Gemini TTS Lambda on AWS
"""

import json
import boto3

# Voice instructions based on specifications
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


def invoke_lambda():
    """Invoke the Gemini TTS Lambda with detective story"""

    lambda_client = boto3.client('lambda', region_name='us-east-1')

    # Prepare the payload
    payload = {
        "text": DETECTIVE_STORY,
        "voiceName": "Alnilam",
        "voiceInstructions": VOICE_INSTRUCTIONS,
        "model": "gemini-2.5-flash-preview-tts",
        "key": "gemini-tts-test/detective_story_alnilam.wav"
    }

    print("🎙️  Invoking Gemini TTS Lambda on AWS...")
    print(f"   Voice: {payload['voiceName']}")
    print(f"   Model: {payload['model']}")
    print(f"   Output: {payload['key']}\n")

    # Invoke the Lambda
    response = lambda_client.invoke(
        FunctionName='FileFlowStack-GeminiTTSLambdaEBA2CD61-KKqmjKMCuBaO',
        InvocationType='RequestResponse',
        Payload=json.dumps(payload)
    )

    # Parse response
    response_payload = json.loads(response['Payload'].read())

    if response['StatusCode'] == 200:
        body = json.loads(response_payload['body'])
        print("✅ Lambda invocation successful!")
        print(f"   Status Code: {response_payload['statusCode']}")
        print(f"   Message: {body['message']}")
        print(f"   Model: {body['model']}")
        print(f"   Voice: {body['voice']}")
        print(f"   Audio Size: {body['audioSizeBytes']:,} bytes")
        print(f"   S3 Location: {body['s3Location']}")
    else:
        print(f"❌ Lambda invocation failed!")
        print(f"   Status Code: {response['StatusCode']}")
        print(f"   Response: {response_payload}")


if __name__ == "__main__":
    invoke_lambda()
