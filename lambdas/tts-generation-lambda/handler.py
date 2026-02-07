#!/usr/bin/env python3
"""
TTS Generation Lambda with Retry Logic
Consumes text chunks from SQS and generates audio files using Gemini TTS
"""

import json
import boto3
import os
from datetime import datetime
import wave
import io
import time
from google import genai
from google.genai import types
from typing import Dict, Any
import logging
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
from google.genai.errors import ClientError


# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')
_genai_client = None

# Environment variables
GEMINI_API_KEY = os.environ['GEMINI_API_KEY']
OUTPUT_BUCKET = os.environ.get('AUDIO_CHUNKS_BUCKET', 'audio-chunks-bucket272765753210')
TEXT_CHUNKS_BUCKET = os.environ.get('TEXT_CHUNKS_BUCKET', 'text-chunks-bucket272765753210')
STITCH_QUEUE_URL = os.environ.get('STITCH_QUEUE_URL')

# Model configuration
TTS_MODEL = os.environ.get('GEMINI_TTS_MODEL', 'gemini-2.5-pro-preview-tts')
TTS_VOICE = os.environ.get('GEMINI_TTS_VOICE', 'Charon')

# Audio format configuration
SAMPLE_RATE = 24000
CHANNELS = 1
SAMPLE_WIDTH = 2

# Retry configuration
MAX_RETRY_ATTEMPTS = 5
INITIAL_WAIT = 10  # seconds
MAX_WAIT = 120  # seconds
EXPONENTIAL_MULTIPLIER = 2


def get_genai_client():
    """Lazy initialization of Gemini client"""
    global _genai_client
    if _genai_client is None:
        _genai_client = genai.Client(api_key=GEMINI_API_KEY)
    return _genai_client


def is_rate_limit_error(exception):
    """Check if exception is a rate limit error"""
    if isinstance(exception, ClientError):
        error_str = str(exception)
        if '429' in error_str or 'RESOURCE_EXHAUSTED' in error_str:
            logger.warning(f"Rate limit hit, will retry: {error_str}")
            return True
    return False


@retry(
    stop=stop_after_attempt(MAX_RETRY_ATTEMPTS),
    wait=wait_exponential(
        multiplier=EXPONENTIAL_MULTIPLIER,
        min=INITIAL_WAIT,
        max=MAX_WAIT
    ),
    retry=retry_if_exception_type(ClientError),
    reraise=True
)
def generate_speech_with_retry(text: str, voice_name: str = TTS_VOICE,
                               model: str = TTS_MODEL) -> bytes:
    """
    Generate speech from text using Gemini TTS with retry logic

    Args:
        text: Text to convert to speech
        voice_name: Voice to use
        model: Gemini model to use

    Returns:
        Raw PCM audio data as bytes
    """
    logger.info(f"Attempting TTS generation with model: {model}, voice: {voice_name}")

    try:
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

        # Check if we got valid response
        if not response or not response.candidates:
            raise ValueError("Empty response from Gemini TTS API")

        if not response.candidates[0].content or not response.candidates[0].content.parts:
            raise ValueError("No audio content in Gemini TTS response")

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

        logger.info(f"Successfully generated {len(pcm_data)} bytes of PCM audio")
        return pcm_data

    except ClientError as e:
        error_str = str(e)
        if '429' in error_str or 'RESOURCE_EXHAUSTED' in error_str:
            logger.warning(f"Rate limit error, will retry with exponential backoff: {error_str}")
            raise  # Let retry decorator handle it
        else:
            logger.error(f"Non-retryable Gemini API error: {error_str}")
            raise

    except Exception as e:
        logger.error(f"Unexpected error in TTS generation: {str(e)}")
        raise


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


def get_text_from_s3(bucket: str, key: str) -> str:
    """
    Retrieve text chunk from S3

    Args:
        bucket: S3 bucket name
        key: S3 object key

    Returns:
        Text content
    """
    logger.info(f"Retrieving text from s3://{bucket}/{key}")

    response = s3_client.get_object(Bucket=bucket, Key=key)
    text = response['Body'].read().decode('utf-8')

    logger.info(f"Retrieved {len(text)} characters of text")
    return text


def store_audio_chunk(job_id: str, chunk_index: int,
                     wav_data: bytes, total_chunks: int) -> str:
    """
    Store audio chunk in S3
    Changed to whole-book processing - removed chapter references

    Returns:
        S3 key of stored audio
    """
    audio_key = f"{job_id}/chunk_{chunk_index:04d}.wav"

    metadata = {
        'job_id': job_id,
        'chunk_index': str(chunk_index),
        'total_chunks': str(total_chunks),
        'audio_size': str(len(wav_data)),
        'sample_rate': str(SAMPLE_RATE),
        'channels': str(CHANNELS)
    }

    logger.info(f"Storing audio chunk to s3://{OUTPUT_BUCKET}/{audio_key}")

    s3_client.put_object(
        Bucket=OUTPUT_BUCKET,
        Key=audio_key,
        Body=wav_data,
        ContentType='audio/wav',
        Metadata=metadata
    )

    return audio_key


def send_to_stitch_queue(job_id: str, chunk_index: int, total_chunks: int,
                         audio_s3_key: str) -> None:
    """
    Send completion message to stitching queue
    Changed to whole-book processing - removed chapter references
    """
    if not STITCH_QUEUE_URL:
        logger.info("No stitch queue configured, skipping notification")
        return

    message = {
        'job_id': job_id,
        'chunk_index': chunk_index,
        'total_chunks': total_chunks,
        'audio_s3_key': audio_s3_key,
        'audio_bucket': OUTPUT_BUCKET,
        'status': 'audio_generated',
        'timestamp': datetime.now().isoformat()
    }

    logger.info(f"Notifying stitch queue about chunk {chunk_index+1}/{total_chunks}")

    # Generate unique MessageDeduplicationId for FIFO queue
    dedup_id = f"{job_id}-chunk{chunk_index:04d}-audio-complete"

    sqs_client.send_message(
        QueueUrl=STITCH_QUEUE_URL,
        MessageBody=json.dumps(message),
        MessageGroupId=job_id,  # Group by job
        MessageDeduplicationId=dedup_id,  # Unique ID to prevent duplicates
        MessageAttributes={
            'job_id': {'StringValue': job_id, 'DataType': 'String'},
            'chunk_index': {'StringValue': str(chunk_index), 'DataType': 'Number'}
        }
    )


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for TTS generation with retry logic
    Triggered by SQS messages from text chunking lambda

    SQS Message format (whole-book processing):
    {
        "job_id": "book-123",
        "chunk_index": 0,
        "total_chunks": 50,
        "text_s3_bucket": "text-chunks-bucket",
        "text_s3_key": "book-123/chunk_0000.txt",
        "source_file": "original.pdf"
    }
    """
    try:
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
                chunk_index = message_body['chunk_index']
                total_chunks = message_body['total_chunks']
                text_s3_key = message_body['text_s3_key']
                text_bucket = message_body.get('text_s3_bucket', TEXT_CHUNKS_BUCKET)

                logger.info(
                    f"Processing TTS for Job {job_id}, "
                    f"Chunk {chunk_index+1}/{total_chunks}"
                )

                # Step 1: Retrieve text from S3
                chunk_text = get_text_from_s3(text_bucket, text_s3_key)

                # Step 2: Rate limiting delay to stay under 10,000 tokens/minute limit
                # Estimate tokens: roughly 1 token per 0.75 words
                word_count = len(chunk_text.split())
                estimated_tokens = int(word_count * 1.3)

                # Calculate delay to spread requests over time
                # Formula: (tokens / rate_limit) * 60 seconds
                delay_seconds = (estimated_tokens / 10000) * 60

                if delay_seconds > 0:
                    logger.info(
                        f"Rate limiting: estimated {estimated_tokens} tokens, "
                        f"waiting {delay_seconds:.1f}s to stay under 10k tokens/minute limit"
                    )
                    time.sleep(delay_seconds)

                # Step 3: Generate TTS audio
                pcm_data = generate_speech_with_retry(chunk_text)
                wav_data = convert_pcm_to_wav(pcm_data)

                logger.info(f"Generated {len(wav_data)} bytes of WAV audio")

                # Step 4: Store audio chunk in S3
                audio_key = store_audio_chunk(
                    job_id, chunk_index,
                    wav_data, total_chunks
                )

                # Step 5: Send to stitching queue
                send_to_stitch_queue(
                    job_id, chunk_index, total_chunks, audio_key
                )

                result = {
                    'job_id': job_id,
                    'chunk_index': chunk_index,
                    'audio_key': audio_key,
                    'audio_size_bytes': len(wav_data),
                    's3_location': f's3://{OUTPUT_BUCKET}/{audio_key}'
                }

                results.append(result)
                logger.info(
                    f"Successfully generated audio for chunk {chunk_index+1}/{total_chunks}"
                )

            except Exception as e:
                logger.error(f"Error processing SQS record: {str(e)}", exc_info=True)

                # Get message receipt handle for visibility timeout extension
                receipt_handle = record.get('receiptHandle')

                # Check if we should retry this message
                retry_count = int(record.get('Attributes', {}).get('ApproximateReceiveCount', '1'))

                if retry_count < 3:  # Allow message to be retried up to 3 times
                    logger.info(f"Message will be retried (attempt {retry_count}/3)")
                    # Don't delete the message, let it become visible again
                    results.append({
                        'error': str(e),
                        'retry_count': retry_count,
                        'status': 'will_retry'
                    })
                else:
                    logger.error(f"Message failed after {retry_count} attempts, moving to DLQ")
                    results.append({
                        'error': str(e),
                        'retry_count': retry_count,
                        'status': 'failed'
                    })

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': f'Processed {len(results)} chunks',
                'results': results
            })
        }

    except Exception as e:
        logger.error(f"Fatal error in lambda handler: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }
