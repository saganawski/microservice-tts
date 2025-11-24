#!/usr/bin/env python3
"""
Chapter Text Extractor and TTS Lambda
Consumes SQS messages, extracts chapter text from Gemini, chunks to 9k tokens,
and generates concatenated audio using Gemini TTS
"""

import json
import boto3
import os
import wave
import io
from google import genai
from google.genai import types
from typing import Dict, Any, List, Optional
import logging
import tiktoken
import urllib.parse
import unicodedata

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
_genai_client = None

# Environment variables
GEMINI_API_KEY = os.environ['GEMINI_API_KEY']
OUTPUT_BUCKET = os.environ.get('PROCESSED_BUCKET_NAME', 'processed-file-bucket272765753210')
TEXT_BUCKET = os.environ.get('TEXT_BUCKET_NAME')

# Model configuration
TEXT_EXTRACTION_MODEL = os.environ.get('GEMINI_TEXT_MODEL', 'gemini-2.5-pro')
TTS_MODEL = os.environ.get('GEMINI_TTS_MODEL', 'gemini-2.5-pro-preview-tts')
TTS_VOICE = os.environ.get('GEMINI_TTS_VOICE', 'Charon')

# Audio format configuration
SAMPLE_RATE = 24000
CHANNELS = 1
SAMPLE_WIDTH = 2

# Token limits
MAX_TOKENS_PER_CHUNK = 4500


def get_genai_client():
    """Lazy initialization of Gemini client"""
    global _genai_client
    if _genai_client is None:
        _genai_client = genai.Client(api_key=GEMINI_API_KEY)
    return _genai_client


def extract_chapter_text(google_file_uri: str, google_file_name: str,
                         chapter_info: Dict[str, Any]) -> str:
    """
    Extract text from specific chapter pages using Gemini

    Args:
        google_file_uri: URI of the uploaded file in Google's system
        google_file_name: Name of the file in Google's system
        chapter_info: Dict with chapter_number, title, start_page, end_page

    Returns:
        Extracted text content
    """
    chapter_num = chapter_info['chapter_number']
    title = chapter_info['title']
    start_page = chapter_info['start_page']
    end_page = chapter_info['end_page']

    logger.info(f"Extracting Chapter {chapter_num}: {title} (pages {start_page}-{end_page})")

    prompt = f"""Extract ALL the text from pages {start_page} to {end_page} of this document.

This is Chapter {chapter_num}: {title}

Return ONLY the actual text content from these pages. Do not add any commentary, explanations, or formatting.
Do not include page numbers, headers, or footers.
Just return the pure story/content text exactly as it appears in the document.
"""

    client = get_genai_client()

    response = client.models.generate_content(
        model=TEXT_EXTRACTION_MODEL,
        contents=[
            types.Content(
                role="user",
                parts=[
                    types.Part.from_uri(
                        file_uri=google_file_uri,
                        mime_type="application/pdf"
                    ),
                    types.Part.from_text(text=prompt)
                ]
            )
        ]
    )

    text = response.text.strip()

    logger.info(f"Extracted {len(text)} characters for Chapter {chapter_num}")

    # Log token usage
    if hasattr(response, 'usage_metadata'):
        usage = response.usage_metadata
        logger.info(
            f"Text extraction token usage - Input: {usage.prompt_token_count}, "
            f"Output: {usage.candidates_token_count}, "
            f"Total: {usage.total_token_count}"
        )

    return text


def chunk_text_by_tokens(text: str, max_tokens: int = MAX_TOKENS_PER_CHUNK) -> List[str]:
    """
    Chunk text into segments with max token count

    Args:
        text: Text to chunk
        max_tokens: Maximum tokens per chunk (default 9k)

    Returns:
        List of text chunks
    """
    # Use tiktoken for accurate token counting (cl100k_base is GPT-4 encoding)
    # This is a reasonable approximation for Gemini as well
    try:
        encoding = tiktoken.get_encoding("cl100k_base")
    except Exception as e:
        logger.warning(f"Failed to load tiktoken encoding: {e}, using character approximation")
        # Fallback: approximate 1 token ≈ 4 characters
        char_limit = max_tokens * 4
        chunks = []
        for i in range(0, len(text), char_limit):
            chunks.append(text[i:i + char_limit])
        logger.info(f"Created {len(chunks)} chunks using character approximation")
        return chunks

    tokens = encoding.encode(text)
    total_tokens = len(tokens)

    logger.info(f"Text has {total_tokens} tokens, chunking to max {max_tokens} tokens each")

    chunks = []
    for i in range(0, total_tokens, max_tokens):
        chunk_tokens = tokens[i:i + max_tokens]
        chunk_text = encoding.decode(chunk_tokens)
        chunks.append(chunk_text)

    logger.info(f"Created {len(chunks)} chunks")
    return chunks


def generate_speech_pcm(text: str, voice_name: str = TTS_VOICE,
                        model: str = TTS_MODEL) -> bytes:
    """
    Generate speech from text using Gemini TTS

    Args:
        text: Text to convert to speech
        voice_name: Voice to use
        model: Gemini model to use

    Returns:
        Raw PCM audio data as bytes
    """
    logger.info(f"Generating speech with model: {model}, voice: {voice_name}")

    # prepend text to read in spanish
    text = "Read this in spanish:\n" + text


    client = get_genai_client()

    response = client.models.generate_content(
        model=model,
        contents=text,
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
            f"TTS token usage - Input: {usage.prompt_token_count}, "
            f"Output: {usage.candidates_token_count}, "
            f"Total: {usage.total_token_count}"
        )

    return pcm_data


def convert_pcm_to_wav(pcm_data: bytes) -> bytes:
    """
    Convert raw PCM audio data to WAV format

    Args:
        pcm_data: Raw PCM audio bytes

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


def concatenate_wav_files(wav_data_list: List[bytes]) -> bytes:
    """
    Concatenate multiple WAV files into a single WAV file

    Args:
        wav_data_list: List of WAV file data as bytes

    Returns:
        Concatenated WAV file as bytes
    """
    if not wav_data_list:
        raise ValueError("No WAV data to concatenate")

    if len(wav_data_list) == 1:
        return wav_data_list[0]

    logger.info(f"Concatenating {len(wav_data_list)} WAV files")

    # Read all WAV files and extract their audio frames
    all_frames = []

    for i, wav_data in enumerate(wav_data_list):
        wav_buffer = io.BytesIO(wav_data)
        with wave.open(wav_buffer, 'rb') as wav_file:
            frames = wav_file.readframes(wav_file.getnframes())
            all_frames.append(frames)
            logger.info(f"Chunk {i+1}: {len(frames)} bytes of audio data")

    # Create concatenated WAV file
    output_buffer = io.BytesIO()
    with wave.open(output_buffer, 'wb') as output_wav:
        output_wav.setnchannels(CHANNELS)
        output_wav.setsampwidth(SAMPLE_WIDTH)
        output_wav.setframerate(SAMPLE_RATE)

        # Write all frames sequentially
        for frames in all_frames:
            output_wav.writeframes(frames)

    result = output_buffer.getvalue()
    logger.info(f"Concatenated WAV size: {len(result)} bytes")

    return result


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for chapter text extraction and TTS generation
    Triggered by SQS messages from orchestrator lambda

    SQS Message format:
    {
        "job_id": "book-123",
        "google_file_uri": "gs://...",
        "google_file_name": "...",
        "chapter": {
            "chapter_number": 1,
            "title": "Introduction",
            "start_page": 1,
            "end_page": 15
        },
        "text_bucket": "text-bucket-name"
    }
    """
    try:
        # Parse SQS event - can have multiple records
        records = event.get('Records', [])

        if not records:
            logger.warning("No SQS records found in event")
            return {
                'statusCode': 400,
                'body': json.dumps({'error': 'No SQS records'})
            }

        results = []

        for record in records:
            try:
                # Parse SQS message body
                message_body = json.loads(record['body'])

                job_id = message_body['job_id']
                google_file_uri = message_body['google_file_uri']
                google_file_name = message_body['google_file_name']
                chapter_info = message_body['chapter']

                chapter_num = chapter_info['chapter_number']
                chapter_title = chapter_info['title']

                logger.info(f"Processing Job {job_id}, Chapter {chapter_num}: {chapter_title}")

                # Step 1: Extract chapter text from Gemini
                chapter_text = extract_chapter_text(
                    google_file_uri,
                    google_file_name,
                    chapter_info
                )

                if not chapter_text or len(chapter_text) < 10:
                    logger.error(f"Extracted text is too short or empty for Chapter {chapter_num}")
                    raise ValueError(f"Failed to extract meaningful text for Chapter {chapter_num}")

                # Step 2: Chunk text to 16k tokens
                text_chunks = chunk_text_by_tokens(chapter_text, MAX_TOKENS_PER_CHUNK)
                logger.info(f"Chapter {chapter_num} split into {len(text_chunks)} chunks")

                # Step 3: Generate TTS for each chunk
                wav_chunks = []
                for i, chunk in enumerate(text_chunks):
                    logger.info(f"Generating TTS for Chapter {chapter_num}, chunk {i+1}/{len(text_chunks)}")

                    # Generate PCM audio
                    pcm_data = generate_speech_pcm(chunk, TTS_VOICE, TTS_MODEL)

                    # Convert to WAV
                    wav_data = convert_pcm_to_wav(pcm_data)
                    wav_chunks.append(wav_data)

                    logger.info(f"Generated {len(wav_data)} bytes of WAV data for chunk {i+1}")

                # Step 4: Concatenate all WAV chunks
                final_wav = concatenate_wav_files(wav_chunks)

                # Step 5: Generate output key and upload to S3
                # Format: jobs/{job_id}/chapter_{num:02d}.wav
                output_key = f"jobs/{job_id}/chapter_{chapter_num:02d}.wav"

                logger.info(f"Uploading to s3://{OUTPUT_BUCKET}/{output_key}")

                s3_client.put_object(
                    Bucket=OUTPUT_BUCKET,
                    Key=output_key,
                    Body=final_wav,
                    ContentType='audio/wav',
                    Metadata={
                        'job_id': job_id,
                        'chapter_number': str(chapter_num),
                        'chapter_title': chapter_title,
                        'chunks_count': str(len(text_chunks))
                    }
                )

                result = {
                    'job_id': job_id,
                    'chapter_number': chapter_num,
                    'chapter_title': chapter_title,
                    'output_key': output_key,
                    'chunks_processed': len(text_chunks),
                    'audio_size_bytes': len(final_wav),
                    's3_location': f's3://{OUTPUT_BUCKET}/{output_key}'
                }

                results.append(result)
                logger.info(f"Successfully processed Chapter {chapter_num}: {output_key}")

            except Exception as e:
                logger.error(f"Error processing SQS record: {str(e)}", exc_info=True)
                # Continue processing other records
                results.append({
                    'error': str(e),
                    'record': record.get('messageId', 'unknown')
                })

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': f'Processed {len(results)} chapters',
                'results': results
            })
        }

    except Exception as e:
        logger.error(f"Fatal error in lambda handler: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }


# For local testing
if __name__ == "__main__":
    test_event = {
        "Records": [{
            "body": json.dumps({
                "job_id": "test-book-001",
                "google_file_uri": "gs://example/file.pdf",
                "google_file_name": "projects/123/files/abc",
                "chapter": {
                    "chapter_number": 1,
                    "title": "Test Chapter",
                    "start_page": 1,
                    "end_page": 5
                },
                "text_bucket": "test-bucket"
            })
        }]
    }

    result = lambda_handler(test_event, None)
    print(json.dumps(json.loads(result['body']), indent=2))
