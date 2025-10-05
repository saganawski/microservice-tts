#!/usr/bin/env python3
"""
Gemini TTS Lambda Handler
Converts text to speech using Google's Gemini TTS API
"""

import json
import boto3
import os
import wave
import io
from google import genai
from google.genai import types
from typing import Dict, Any, Optional
import logging

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients lazily for Lambda optimization
_tts_client = None
s3_client = boto3.client('s3')


def get_tts_client():
    """Lazy initialization of TTS client"""
    global _tts_client
    if _tts_client is None:
        _tts_client = genai.Client()  # Picks up GEMINI_API_KEY from environment
    return _tts_client

# Model configuration - easily swappable
DEFAULT_MODEL = os.environ.get('GEMINI_TTS_MODEL', 'gemini-2.5-flash-preview-tts')
DEFAULT_VOICE = os.environ.get('GEMINI_TTS_VOICE', 'Charon')

# Audio format configuration
SAMPLE_RATE = 24000
CHANNELS = 1
SAMPLE_WIDTH = 2

# Hardcoded output bucket for testing
OUTPUT_BUCKET = 'processed-file-bucket272765753210'


def convert_pcm_to_wav(pcm_data: bytes) -> bytes:
    """
    Convert raw PCM audio data to WAV format

    Args:
        pcm_data: Raw PCM audio bytes from Gemini API

    Returns:
        WAV file as bytes
    """
    wav_buffer = io.BytesIO()
    with wave.open(wav_buffer, 'wb') as wav_file:
        wav_file.setnchannels(CHANNELS)
        wav_file.setsampwidth(SAMPLE_WIDTH)
        wav_file.setframerate(SAMPLE_RATE)
        wav_file.writeframes(pcm_data)

    return wav_buffer.getvalue()


def generate_speech(
    text: str,
    voice_name: str = DEFAULT_VOICE,
    model: str = DEFAULT_MODEL,
    voice_instructions: Optional[str] = None
) -> bytes:
    """
    Generate speech from text using Gemini TTS

    Args:
        text: Text to convert to speech
        voice_name: Voice to use (default from env or 'Charon')
        model: Gemini model to use (flash or pro)
        voice_instructions: Optional instructions for voice style/affect

    Returns:
        Raw PCM audio data as bytes
    """
    # Prepare the content with optional voice instructions
    if voice_instructions:
        content = f"{voice_instructions}\n\n{text}"
    else:
        content = text

    logger.info(f"Generating speech with model: {model}, voice: {voice_name}")

    tts_client = get_tts_client()
    response = tts_client.models.generate_content(
        model=model,
        contents=content,
        config=types.GenerateContentConfig(
            response_modalities=["AUDIO"],
            speech_config=types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(
                        voice_name=voice_name
                    )
                )
            )
        )
    )

    # Extract PCM data
    pcm_data = response.candidates[0].content.parts[0].inline_data.data

    # Log token usage
    if hasattr(response, 'usage_metadata'):
        usage = response.usage_metadata
        logger.info(
            f"Token usage - Input: {usage.prompt_token_count}, "
            f"Output: {usage.candidates_token_count}, "
            f"Total: {usage.total_token_count}"
        )

    return pcm_data


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for Gemini TTS audio generation
    Triggered by S3 events from chapters bucket

    S3 Event format:
    {
        "Records": [{
            "s3": {
                "bucket": {"name": "chapters-bucket..."},
                "object": {"key": "book-id/chapter_01.md"}
            }
        }]
    }
    """
    try:
        # Extract S3 event details
        record = event['Records'][0]
        bucket_name = record['s3']['bucket']['name']
        object_key = record['s3']['object']['key']

        logger.info(f"Processing S3 event - Bucket: {bucket_name}, Key: {object_key}")

        # Skip metadata.json files - copy to processed bucket
        if object_key.endswith('metadata.json'):
            logger.info("Detected metadata.json, copying to processed bucket")
            s3_client.copy_object(
                Bucket=OUTPUT_BUCKET,
                CopySource={'Bucket': bucket_name, 'Key': object_key},
                Key=object_key
            )
            return {
                'statusCode': 200,
                'body': json.dumps({'message': 'Metadata copied successfully'})
            }

        # Skip non-markdown files
        if not object_key.endswith('.md'):
            logger.info(f"Skipping non-markdown file: {object_key}")
            return {
                'statusCode': 200,
                'body': json.dumps({'message': f'Skipped: {object_key}'})
            }

        # Download markdown content from chapters bucket
        response = s3_client.get_object(Bucket=bucket_name, Key=object_key)
        text = response['Body'].read().decode('utf-8')

        logger.info(f"Downloaded {len(text)} characters from {object_key}")

        # Generate output key (replace .md with .wav)
        output_key = object_key.replace('.md', '.wav')

        logger.info(f"Generating TTS - Voice: {DEFAULT_VOICE}, Model: {DEFAULT_MODEL}")

        # Generate audio
        pcm_data = generate_speech(text, DEFAULT_VOICE, DEFAULT_MODEL, voice_instructions=None)

        # Convert to WAV
        wav_data = convert_pcm_to_wav(pcm_data)

        # Upload to processed bucket
        s3_client.put_object(
            Bucket=OUTPUT_BUCKET,
            Key=output_key,
            Body=wav_data,
            ContentType='audio/wav'
        )

        s3_location = f's3://{OUTPUT_BUCKET}/{output_key}'
        logger.info(f"Successfully uploaded audio to: {s3_location}")

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': 'Audio generated successfully',
                'model': DEFAULT_MODEL,
                'voice': DEFAULT_VOICE,
                'audioSizeBytes': len(wav_data),
                's3Location': s3_location
            })
        }

    except Exception as e:
        logger.error(f"Error processing TTS request: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }


# For local testing
if __name__ == "__main__":
    # Test event
    test_event = {
        "text": "Hello world! This is a test of the Gemini TTS system.",
        "voiceName": "Charon",
        "model": "gemini-2.5-flash-preview-tts"
    }

    result = lambda_handler(test_event, None)
    print(json.dumps(json.loads(result['body']), indent=2))
