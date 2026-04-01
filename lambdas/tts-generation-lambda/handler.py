#!/usr/bin/env python3
"""
TTS Generation Lambda with Retry Logic
Consumes text chunks from SQS and generates audio files using Gemini, Orpheus, or MOSS TTS
"""

import json
import boto3
import os
import base64
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
import requests


# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')
dynamodb = boto3.resource('dynamodb')
secrets_client = boto3.client('secretsmanager')
_genai_client = None

# Load Gemini API key from Secrets Manager at cold start
_secret_name = os.environ.get('GEMINI_SECRET_NAME', 'microservice-tts/gemini-api-key')
_secret_resp = secrets_client.get_secret_value(SecretId=_secret_name)
GEMINI_API_KEY = json.loads(_secret_resp['SecretString'])['api_key']
JOB_STATUS_TABLE = os.environ.get('JOB_STATUS_TABLE', '')
OUTPUT_BUCKET = os.environ.get('AUDIO_CHUNKS_BUCKET', 'audio-chunks-bucket272765753210')
TEXT_CHUNKS_BUCKET = os.environ.get('TEXT_CHUNKS_BUCKET', 'text-chunks-bucket272765753210')
STITCH_QUEUE_URL = os.environ.get('STITCH_QUEUE_URL')

# TTS provider configuration
TTS_PROVIDER = os.environ.get('TTS_PROVIDER', 'gemini')  # 'gemini', 'orpheus', or 'moss'
ORPHEUS_API_URL_ENV = os.environ.get('ORPHEUS_API_URL', '')  # static fallback
ORPHEUS_SSM_PARAM = os.environ.get('ORPHEUS_SSM_PARAM', '/microservice-tts/orpheus-api-url')
ORPHEUS_VOICE = os.environ.get('ORPHEUS_VOICE', 'tara')
ORPHEUS_TIMEOUT = 300  # 5 minutes for long chunks

# MOSS TTS configuration (same REST API contract as Orpheus)
MOSS_API_URL_ENV = os.environ.get('MOSS_API_URL', '')  # static fallback
MOSS_SSM_PARAM = os.environ.get('MOSS_SSM_PARAM', '/microservice-tts/moss-api-url')
MOSS_TIMEOUT = int(os.environ.get('MOSS_TIMEOUT', '300'))  # 5 minutes for long chunks
MOSS_VOICE_REFERENCE = os.environ.get('MOSS_VOICE_REFERENCE', 'seed_045')  # default voice for cloning

# SSM-based dynamic URL cache (IP changes on EC2 restart)
_cached_orpheus_url = None
_orpheus_url_fetched_at = 0
_cached_moss_url = None
_moss_url_fetched_at = 0
_URL_CACHE_TTL = 300  # refresh every 5 minutes


def _get_ec2_api_url(ssm_param, env_fallback, cache_ref):
    """Get EC2-hosted API URL from SSM Parameter Store with caching, fallback to env var."""
    cached_url, fetched_at = cache_ref

    now = time.time()
    if cached_url and (now - fetched_at) < _URL_CACHE_TTL:
        return cached_url, fetched_at

    try:
        ssm = boto3.client('ssm')
        resp = ssm.get_parameter(Name=ssm_param)
        url = resp['Parameter']['Value']
        logger.info(f"Fetched API URL from SSM ({ssm_param}): {url}")
        return url, now
    except Exception as e:
        logger.warning(f"Failed to read URL from SSM ({ssm_param}): {e}")
        if cached_url:
            return cached_url, fetched_at
        return env_fallback, 0


def get_orpheus_api_url():
    """Get Orpheus API URL from SSM Parameter Store with caching, fallback to env var."""
    global _cached_orpheus_url, _orpheus_url_fetched_at
    _cached_orpheus_url, _orpheus_url_fetched_at = _get_ec2_api_url(
        ORPHEUS_SSM_PARAM, ORPHEUS_API_URL_ENV,
        (_cached_orpheus_url, _orpheus_url_fetched_at)
    )
    return _cached_orpheus_url


def get_moss_api_url():
    """Get MOSS API URL from SSM Parameter Store with caching, fallback to env var."""
    global _cached_moss_url, _moss_url_fetched_at
    _cached_moss_url, _moss_url_fetched_at = _get_ec2_api_url(
        MOSS_SSM_PARAM, MOSS_API_URL_ENV,
        (_cached_moss_url, _moss_url_fetched_at)
    )
    return _cached_moss_url

# Gemini model configuration
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


def update_job_status(job_id, status, **kwargs):
    if not JOB_STATUS_TABLE:
        return
    try:
        table = dynamodb.Table(JOB_STATUS_TABLE)
        update_expr = 'SET #s = :s, updated_at = :u'
        expr_values = {':s': status, ':u': datetime.now().isoformat()}
        expr_names = {'#s': 'status'}
        for k, v in kwargs.items():
            update_expr += f', {k} = :{k}'
            expr_values[f':{k}'] = v
        table.update_item(
            Key={'job_id': job_id},
            UpdateExpression=update_expr,
            ExpressionAttributeValues=expr_values,
            ExpressionAttributeNames=expr_names,
        )
    except Exception as e:
        logger.warning(f"Failed to update job status: {e}")


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


class OrpheusError(Exception):
    """Raised when Orpheus API call fails."""
    pass


class MossError(Exception):
    """Raised when MOSS TTS API call fails."""
    pass


# Voice reference bucket for voice cloning
VOICE_REFERENCE_BUCKET = 'tts-eval-data-272765753210'
VOICE_REFERENCE_PREFIX = 'voices'


def generate_speech_moss(text: str, seed: int | None = None, reference_audio: str | None = None) -> bytes:
    """
    Generate speech using the self-hosted MOSS-TTS 8B API.
    Returns raw WAV bytes (24kHz, 16-bit PCM, mono).
    Same REST API contract as Orpheus.

    Args:
        reference_audio: Base64-encoded WAV data for voice cloning.
    """
    moss_base = get_moss_api_url()
    url = f"{moss_base.rstrip('/')}/generate"
    logger.info(f"Calling MOSS API at {url}, text length: {len(text)} chars, seed: {seed}, has_reference_audio: {reference_audio is not None}")

    payload = {"text": text}
    if seed is not None:
        payload["seed"] = seed
    if reference_audio is not None:
        payload["reference_audio"] = reference_audio

    try:
        response = requests.post(
            url,
            json=payload,
            timeout=MOSS_TIMEOUT,
        )
        response.raise_for_status()
    except requests.exceptions.ConnectionError as e:
        raise MossError(f"MOSS connection refused: {e}")
    except requests.exceptions.Timeout as e:
        raise MossError(f"MOSS request timed out after {MOSS_TIMEOUT}s: {e}")
    except requests.exceptions.HTTPError as e:
        status = e.response.status_code if e.response is not None else 'unknown'
        body = e.response.text[:500] if e.response is not None else ''
        if status == 503:
            raise MossError(f"MOSS server busy (503): {body}")
        raise MossError(f"MOSS HTTP {status}: {body}")

    wav_data = response.content
    if len(wav_data) < 44:  # WAV header is 44 bytes minimum
        raise MossError(f"MOSS returned invalid audio ({len(wav_data)} bytes)")

    duration = response.headers.get('X-Audio-Duration-Seconds', 'unknown')
    request_id = response.headers.get('X-Request-Id', 'unknown')
    logger.info(f"MOSS generated {len(wav_data)} bytes of WAV audio (duration: {duration}s, req: {request_id})")
    return wav_data


def generate_speech_orpheus(text: str, voice: str = ORPHEUS_VOICE) -> bytes:
    """
    Generate speech using the self-hosted Orpheus 3B API.
    Returns raw WAV bytes (24kHz, 16-bit PCM, mono).
    """
    orpheus_base = get_orpheus_api_url()
    url = f"{orpheus_base.rstrip('/')}/generate"
    logger.info(f"Calling Orpheus API at {url}, voice: {voice}, text length: {len(text)} chars")

    try:
        response = requests.post(
            url,
            json={"text": text, "voice": voice},
            timeout=ORPHEUS_TIMEOUT,
        )
        response.raise_for_status()
    except requests.exceptions.ConnectionError as e:
        raise OrpheusError(f"Orpheus connection refused: {e}")
    except requests.exceptions.Timeout as e:
        raise OrpheusError(f"Orpheus request timed out after {ORPHEUS_TIMEOUT}s: {e}")
    except requests.exceptions.HTTPError as e:
        status = e.response.status_code if e.response is not None else 'unknown'
        body = e.response.text[:500] if e.response is not None else ''
        raise OrpheusError(f"Orpheus HTTP {status}: {body}")

    wav_data = response.content
    if len(wav_data) < 44:  # WAV header is 44 bytes minimum
        raise OrpheusError(f"Orpheus returned invalid audio ({len(wav_data)} bytes)")

    duration = response.headers.get('X-Audio-Duration-Seconds', 'unknown')
    logger.info(f"Orpheus generated {len(wav_data)} bytes of WAV audio (duration: {duration}s)")
    return wav_data


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
def generate_speech_gemini(text: str, voice_name: str = TTS_VOICE,
                           model: str = TTS_MODEL) -> bytes:
    """
    Generate speech from text using Gemini TTS with retry logic.
    Returns raw PCM audio data as bytes.
    """
    logger.info(f"Attempting Gemini TTS with model: {model}, voice: {voice_name}")

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

        if not response or not response.candidates:
            raise ValueError("Empty response from Gemini TTS API")

        if not response.candidates[0].content or not response.candidates[0].content.parts:
            raise ValueError("No audio content in Gemini TTS response")

        pcm_data = response.candidates[0].content.parts[0].inline_data.data

        if hasattr(response, 'usage_metadata'):
            usage = response.usage_metadata
            logger.info(
                f"TTS token usage - Input: {usage.prompt_token_count}, "
                f"Output: {usage.candidates_token_count}, "
                f"Total: {usage.total_token_count}"
            )

        logger.info(f"Gemini generated {len(pcm_data)} bytes of PCM audio")
        return pcm_data

    except ClientError as e:
        error_str = str(e)
        if '429' in error_str or 'RESOURCE_EXHAUSTED' in error_str:
            logger.warning(f"Rate limit error, will retry with exponential backoff: {error_str}")
            raise
        else:
            logger.error(f"Non-retryable Gemini API error: {error_str}")
            raise

    except Exception as e:
        logger.error(f"Unexpected error in Gemini TTS generation: {str(e)}")
        raise


def download_voice_reference_b64(voice: str) -> str:
    """Download voice reference WAV from S3 and return as base64 string for MOSS voice cloning."""
    s3_key = f"{VOICE_REFERENCE_PREFIX}/{voice}.wav"
    logger.info(f"Downloading voice reference s3://{VOICE_REFERENCE_BUCKET}/{s3_key}")
    response = s3_client.get_object(Bucket=VOICE_REFERENCE_BUCKET, Key=s3_key)
    wav_bytes = response['Body'].read()
    b64 = base64.b64encode(wav_bytes).decode('ascii')
    logger.info(f"Voice reference loaded: {len(wav_bytes)} bytes -> {len(b64)} chars base64")
    return b64


def generate_speech_with_retry(text: str, seed: int | None = None, voice: str | None = None) -> bytes:
    """
    Generate speech using the configured provider.
    EC2-hosted providers (Orpheus/MOSS) fall back to Gemini on failure.

    Returns:
        WAV audio data as bytes.
    """
    provider = TTS_PROVIDER.lower()

    if provider == 'moss':
        moss_url = get_moss_api_url()
        if not moss_url:
            logger.error("TTS_PROVIDER=moss but no MOSS URL available (SSM or env), falling back to Gemini")
            pcm_data = generate_speech_gemini(text)
            return convert_pcm_to_wav(pcm_data)

        # Always use voice cloning for consistent voice across all chunks
        # Use explicit voice from message, or fall back to configured default
        voice_name = voice or MOSS_VOICE_REFERENCE
        reference_audio = None
        try:
            reference_audio = download_voice_reference_b64(voice_name)
        except Exception as e:
            logger.warning(f"Failed to download voice reference '{voice_name}': {e}")

        try:
            # MOSS returns WAV directly
            return generate_speech_moss(text, seed=seed, reference_audio=reference_audio)
        except MossError as e:
            logger.warning(f"MOSS failed, falling back to Gemini: {e}")
            pcm_data = generate_speech_gemini(text)
            return convert_pcm_to_wav(pcm_data)

    elif provider == 'orpheus':
        orpheus_url = get_orpheus_api_url()
        if not orpheus_url:
            logger.error("TTS_PROVIDER=orpheus but no Orpheus URL available (SSM or env), falling back to Gemini")
            pcm_data = generate_speech_gemini(text)
            return convert_pcm_to_wav(pcm_data)

        try:
            # Orpheus returns WAV directly
            return generate_speech_orpheus(text, ORPHEUS_VOICE)
        except OrpheusError as e:
            logger.warning(f"Orpheus failed, falling back to Gemini: {e}")
            pcm_data = generate_speech_gemini(text)
            return convert_pcm_to_wav(pcm_data)
    else:
        # Gemini returns raw PCM, wrap in WAV
        pcm_data = generate_speech_gemini(text)
        return convert_pcm_to_wav(pcm_data)


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
                seed = message_body.get('seed')
                voice = message_body.get('voice')

                logger.info(
                    f"Processing TTS for Job {job_id}, "
                    f"Chunk {chunk_index+1}/{total_chunks}"
                )

                # Step 1: Retrieve text from S3
                chunk_text = get_text_from_s3(text_bucket, text_s3_key)

                # Step 2: Rate limiting delay (only needed for Gemini, not EC2-hosted models)
                if TTS_PROVIDER.lower() == 'gemini':
                    word_count = len(chunk_text.split())
                    estimated_tokens = int(word_count * 1.3)
                    delay_seconds = (estimated_tokens / 10000) * 60
                    if delay_seconds > 0:
                        logger.info(
                            f"Gemini rate limiting: estimated {estimated_tokens} tokens, "
                            f"waiting {delay_seconds:.1f}s"
                        )
                        time.sleep(delay_seconds)

                # Step 3: Generate TTS audio (returns WAV for both providers)
                wav_data = generate_speech_with_retry(chunk_text, seed=seed, voice=voice)

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

                # Update job status with chunk progress
                completed = chunk_index + 1
                # Progress: 25% (chunking done) + up to 65% for TTS generation
                pct = 25 + int((completed / total_chunks) * 65)
                update_job_status(job_id, 'GENERATING',
                                  completed_chunks=completed,
                                  progress=min(pct, 90))

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
                    results.append({
                        'error': str(e),
                        'retry_count': retry_count,
                        'status': 'will_retry'
                    })
                else:
                    logger.error(f"Message failed after {retry_count} attempts, moving to DLQ")
                    update_job_status(
                        message_body.get('job_id', 'unknown'), 'FAILED',
                        error_message=f"TTS generation failed after {retry_count} attempts: {str(e)[:300]}")
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
