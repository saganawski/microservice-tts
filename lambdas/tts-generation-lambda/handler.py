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
import math
import struct
from google import genai
from google.genai import types
from typing import Dict, Any, Optional, List, Tuple
import logging
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
from google.genai.errors import ClientError


# =============================================================================
# Audio Validation Exception Classes
# =============================================================================

class AudioValidationError(Exception):
    """Base class for audio validation errors"""
    pass


class AudioDurationError(AudioValidationError):
    """Audio duration outside expected range"""
    pass


class AudioSilenceError(AudioValidationError):
    """Audio contains too much silence"""
    pass


class AudioSizeError(AudioValidationError):
    """Audio file size outside expected range"""
    pass


class AudioFormatError(AudioValidationError):
    """Audio format is invalid or corrupted"""
    pass

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')
sns_client = boto3.client('sns')
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

# =============================================================================
# Audio Validation Configuration (Calibrated from actual data)
# =============================================================================

AUDIO_VALIDATION_CONFIG = {
    # Duration estimation (calibrated from actual Gemini Spanish TTS data)
    'WORDS_PER_MINUTE_TTS': 250,         # Calibrated: ~250 wpm for Spanish TTS
    'DURATION_TOLERANCE_PERCENT': 0.20,  # 20% tolerance

    # Silence detection (lenient)
    'SILENCE_THRESHOLD_RMS': 100,        # RMS below this is considered silence
    'MAX_SILENCE_RATIO': 0.25,           # Max 25% silence allowed
    'SAMPLE_WINDOW_MS': 100,             # Window size for RMS calculation

    # Size validation (calibrated from actual 30MB chunks)
    'EXPECTED_SIZE_BYTES': 31446330,     # Calibrated: exactly 30.0 MB for full chunks
    'SIZE_TOLERANCE_PERCENT': 0.10,      # 10% tolerance (good chunks are consistent)
    'MIN_AUDIO_SIZE_BYTES': 5000000,     # Min 5MB (catches severe truncation)

    # Retry configuration for validation failures
    'MAX_VALIDATION_RETRIES': 3,
    'VALIDATION_RETRY_DELAY': 5,         # Base delay seconds

    # SNS alerting
    'SNS_ALERT_TOPIC_ARN': os.environ.get('VALIDATION_ALERT_TOPIC_ARN', ''),
}


# =============================================================================
# Validation Functions
# =============================================================================

def estimate_audio_duration(text: str) -> float:
    """
    Estimate expected audio duration from text content.

    Based on calibrated Gemini Spanish TTS speaking rate (~250 words/minute).

    Args:
        text: Input text to be converted to speech

    Returns:
        Estimated duration in seconds
    """
    word_count = len(text.split())
    words_per_second = AUDIO_VALIDATION_CONFIG['WORDS_PER_MINUTE_TTS'] / 60
    estimated_duration = word_count / words_per_second
    return estimated_duration


def validate_wav_format(wav_data: bytes) -> Dict[str, Any]:
    """
    Validate WAV file format and integrity.

    Args:
        wav_data: WAV file bytes

    Returns:
        Validation result dict with 'valid', format details, and 'message'
    """
    try:
        wav_buffer = io.BytesIO(wav_data)
        with wave.open(wav_buffer, 'rb') as wav_file:
            channels = wav_file.getnchannels()
            sample_width = wav_file.getsampwidth()
            frame_rate = wav_file.getframerate()
            frames = wav_file.getnframes()

            # Validate expected format
            format_valid = (
                channels == CHANNELS and
                sample_width == SAMPLE_WIDTH and
                frame_rate == SAMPLE_RATE and
                frames > 0
            )

            return {
                'valid': format_valid,
                'channels': channels,
                'sample_width': sample_width,
                'frame_rate': frame_rate,
                'frames': frames,
                'expected_channels': CHANNELS,
                'expected_sample_width': SAMPLE_WIDTH,
                'expected_frame_rate': SAMPLE_RATE,
                'message': f"Format: {frame_rate}Hz, {channels}ch, {sample_width*8}bit, {frames} frames"
            }
    except Exception as e:
        return {
            'valid': False,
            'error': str(e),
            'message': f"WAV format error: {str(e)}"
        }


def validate_audio_duration(wav_data: bytes, expected_duration: float,
                           is_last_chunk: bool = False) -> Dict[str, Any]:
    """
    Validate audio duration against expected value.

    Args:
        wav_data: WAV file bytes
        expected_duration: Expected duration in seconds
        is_last_chunk: If True, use more lenient validation (50% tolerance)

    Returns:
        Validation result dict with 'valid', 'actual', 'expected', 'ratio'
    """
    try:
        wav_buffer = io.BytesIO(wav_data)
        with wave.open(wav_buffer, 'rb') as wav_file:
            frames = wav_file.getnframes()
            rate = wav_file.getframerate()
            actual_duration = frames / rate

        tolerance = AUDIO_VALIDATION_CONFIG['DURATION_TOLERANCE_PERCENT']
        if is_last_chunk:
            tolerance = 0.50  # More lenient for last chunk

        min_duration = expected_duration * (1 - tolerance)
        max_duration = expected_duration * (1 + tolerance)

        is_valid = min_duration <= actual_duration <= max_duration
        ratio = actual_duration / expected_duration if expected_duration > 0 else 0

        return {
            'valid': is_valid,
            'actual_duration': actual_duration,
            'expected_duration': expected_duration,
            'ratio': ratio,
            'tolerance': tolerance,
            'message': f"Duration {actual_duration:.1f}s vs expected {expected_duration:.1f}s (ratio: {ratio:.2f})"
        }
    except Exception as e:
        return {
            'valid': False,
            'error': str(e),
            'message': f"Duration validation error: {str(e)}"
        }


def validate_audio_size(wav_data: bytes, expected_duration: float,
                       is_last_chunk: bool = False) -> Dict[str, Any]:
    """
    Validate audio file size against expected value based on duration.

    Expected size = sample_rate * channels * sample_width * duration + header

    Args:
        wav_data: WAV file bytes
        expected_duration: Expected duration in seconds
        is_last_chunk: If True, use more lenient validation

    Returns:
        Validation result dict
    """
    actual_size = len(wav_data)

    # Calculate expected size: 24kHz * 1 channel * 2 bytes * duration + ~44 byte header
    expected_size = int(SAMPLE_RATE * CHANNELS * SAMPLE_WIDTH * expected_duration) + 44

    tolerance = AUDIO_VALIDATION_CONFIG['SIZE_TOLERANCE_PERCENT']
    if is_last_chunk:
        tolerance = 0.50  # More lenient for last chunk

    min_size = max(
        expected_size * (1 - tolerance),
        AUDIO_VALIDATION_CONFIG['MIN_AUDIO_SIZE_BYTES']
    )
    max_size = expected_size * (1 + tolerance)

    is_valid = min_size <= actual_size <= max_size
    ratio = actual_size / expected_size if expected_size > 0 else 0

    return {
        'valid': is_valid,
        'actual_size': actual_size,
        'expected_size': expected_size,
        'ratio': ratio,
        'min_size': int(min_size),
        'max_size': int(max_size),
        'message': f"Size {actual_size/1024/1024:.2f}MB vs expected {expected_size/1024/1024:.2f}MB (ratio: {ratio:.2f})"
    }


def calculate_rms_energy(samples: List[int]) -> float:
    """
    Calculate Root Mean Square energy of audio samples.

    Args:
        samples: List of 16-bit audio samples

    Returns:
        RMS energy value
    """
    if not samples:
        return 0.0
    sum_squares = sum(s * s for s in samples)
    return math.sqrt(sum_squares / len(samples))


def detect_silence_ratio(wav_data: bytes) -> Dict[str, Any]:
    """
    Analyze audio for silence content using sliding window RMS analysis.

    Args:
        wav_data: WAV file bytes

    Returns:
        Analysis result with silence ratio and metrics
    """
    try:
        wav_buffer = io.BytesIO(wav_data)
        with wave.open(wav_buffer, 'rb') as wav_file:
            frames = wav_file.readframes(wav_file.getnframes())
            sample_rate = wav_file.getframerate()

        # Convert to 16-bit samples
        num_samples = len(frames) // 2
        samples = list(struct.unpack(f'{num_samples}h', frames))

        # Calculate window size
        window_ms = AUDIO_VALIDATION_CONFIG['SAMPLE_WINDOW_MS']
        window_samples = int(sample_rate * window_ms / 1000)
        threshold = AUDIO_VALIDATION_CONFIG['SILENCE_THRESHOLD_RMS']

        silent_windows = 0
        total_windows = 0
        rms_values = []

        for i in range(0, len(samples) - window_samples, window_samples):
            window = samples[i:i + window_samples]
            rms = calculate_rms_energy(window)
            rms_values.append(rms)
            total_windows += 1
            if rms < threshold:
                silent_windows += 1

        silence_ratio = silent_windows / total_windows if total_windows > 0 else 1.0
        avg_rms = sum(rms_values) / len(rms_values) if rms_values else 0
        max_rms = max(rms_values) if rms_values else 0

        max_silence = AUDIO_VALIDATION_CONFIG['MAX_SILENCE_RATIO']
        is_valid = silence_ratio <= max_silence

        return {
            'valid': is_valid,
            'silence_ratio': silence_ratio,
            'max_allowed': max_silence,
            'silent_windows': silent_windows,
            'total_windows': total_windows,
            'avg_rms': avg_rms,
            'max_rms': max_rms,
            'message': f"Silence ratio {silence_ratio:.1%} (max allowed: {max_silence:.1%})"
        }
    except Exception as e:
        return {
            'valid': False,
            'error': str(e),
            'silence_ratio': 1.0,
            'message': f"Silence detection error: {str(e)}"
        }


def validate_audio_output(wav_data: bytes, text: str,
                         chunk_index: int, total_chunks: int) -> Dict[str, Any]:
    """
    Perform comprehensive audio validation.

    Args:
        wav_data: Generated WAV audio bytes
        text: Original text that was converted
        chunk_index: Current chunk index
        total_chunks: Total number of chunks

    Returns:
        Comprehensive validation result
    """
    is_last_chunk = (chunk_index == total_chunks - 1)
    expected_duration = estimate_audio_duration(text)

    # Run all validations
    format_result = validate_wav_format(wav_data)
    duration_result = validate_audio_duration(wav_data, expected_duration, is_last_chunk)
    size_result = validate_audio_size(wav_data, expected_duration, is_last_chunk)
    silence_result = detect_silence_ratio(wav_data)

    # Aggregate results
    all_valid = all([
        format_result['valid'],
        duration_result['valid'],
        size_result['valid'],
        silence_result['valid']
    ])

    # Determine failure reasons
    failure_reasons = []
    if not format_result['valid']:
        failure_reasons.append(('format', format_result['message']))
    if not duration_result['valid']:
        failure_reasons.append(('duration', duration_result['message']))
    if not size_result['valid']:
        failure_reasons.append(('size', size_result['message']))
    if not silence_result['valid']:
        failure_reasons.append(('silence', silence_result['message']))

    return {
        'valid': all_valid,
        'format': format_result,
        'duration': duration_result,
        'size': size_result,
        'silence': silence_result,
        'failure_reasons': failure_reasons,
        'is_last_chunk': is_last_chunk,
        'expected_duration': expected_duration,
        'chunk_index': chunk_index,
        'total_chunks': total_chunks
    }


# =============================================================================
# Validation Retry Strategy
# =============================================================================

class ValidationRetryStrategy:
    """Determines retry strategy based on validation failure type"""

    STRATEGIES = {
        'format': {
            'max_retries': 2,
            'delay_multiplier': 1.0,
            'should_retry': True,
            'description': 'WAV format error - likely API issue'
        },
        'duration': {
            'max_retries': 3,
            'delay_multiplier': 1.5,
            'should_retry': True,
            'description': 'Duration mismatch - API may have truncated response'
        },
        'size': {
            'max_retries': 3,
            'delay_multiplier': 1.5,
            'should_retry': True,
            'description': 'Size mismatch - incomplete audio generation'
        },
        'silence': {
            'max_retries': 2,
            'delay_multiplier': 2.0,
            'should_retry': True,
            'description': 'Silent audio - API generated empty content'
        }
    }

    @classmethod
    def get_strategy(cls, failure_type: str) -> Dict[str, Any]:
        return cls.STRATEGIES.get(failure_type, {
            'max_retries': 1,
            'delay_multiplier': 1.0,
            'should_retry': True,
            'description': 'Unknown failure'
        })

    @classmethod
    def should_retry(cls, failure_reasons: List[Tuple[str, str]],
                    attempt: int) -> Tuple[bool, float, str]:
        """
        Determine if retry should occur and with what delay.

        Args:
            failure_reasons: List of (failure_type, message) tuples
            attempt: Current attempt number (0-indexed)

        Returns:
            (should_retry, delay_seconds, reason)
        """
        if not failure_reasons:
            return (False, 0, 'No failures')

        # Get the most restrictive strategy
        max_retries = float('inf')
        max_delay_mult = 0

        for failure_type, _ in failure_reasons:
            strategy = cls.get_strategy(failure_type)
            max_retries = min(max_retries, strategy['max_retries'])
            max_delay_mult = max(max_delay_mult, strategy['delay_multiplier'])

        if attempt >= max_retries:
            return (False, 0, f'Max retries ({int(max_retries)}) exceeded')

        base_delay = AUDIO_VALIDATION_CONFIG['VALIDATION_RETRY_DELAY']
        delay = base_delay * max_delay_mult * (attempt + 1)

        failure_types = [f[0] for f in failure_reasons]
        return (True, delay, f'Retrying for: {", ".join(failure_types)}')


# =============================================================================
# Observability and Alerting
# =============================================================================

def log_validation_metrics(validation_result: Dict[str, Any], attempt: int) -> None:
    """
    Log validation metrics for CloudWatch Logs Insights analysis.

    Metrics logged in structured format for easy querying.
    """
    metrics = {
        'audio_validation': {
            'passed': 1 if validation_result['valid'] else 0,
            'attempt': attempt + 1,
            'duration_ratio': validation_result['duration'].get('ratio', 0),
            'silence_ratio': validation_result['silence'].get('silence_ratio', 0),
            'size_ratio': validation_result['size'].get('ratio', 0),
            'actual_duration_seconds': validation_result['duration'].get('actual_duration', 0),
            'expected_duration_seconds': validation_result['duration'].get('expected_duration', 0),
            'avg_rms_energy': validation_result['silence'].get('avg_rms', 0),
            'chunk_index': validation_result['chunk_index'],
            'total_chunks': validation_result['total_chunks'],
            'is_last_chunk': validation_result['is_last_chunk']
        }
    }

    # Log in structured format for CloudWatch Logs Insights
    logger.info(f"AUDIO_VALIDATION_METRICS: {json.dumps(metrics)}")

    # Emit specific warnings for concerning metrics
    if validation_result['duration'].get('ratio', 1) < 0.7:
        logger.warning(
            f"AUDIO_UNDERSIZED: Duration ratio {validation_result['duration'].get('ratio', 0):.2f} "
            f"is significantly below expected"
        )

    if validation_result['silence'].get('silence_ratio', 0) > 0.10:
        logger.warning(
            f"AUDIO_HIGH_SILENCE: Silence ratio {validation_result['silence'].get('silence_ratio', 0):.1%} "
            f"exceeds 10%"
        )


def send_validation_alert(job_id: str, chunk_index: int, total_chunks: int,
                         failure_reasons: List[Tuple[str, str]],
                         attempts: List[Dict]) -> None:
    """
    Send SNS alert when validation fails after all retries.

    Args:
        job_id: Job identifier
        chunk_index: Failed chunk index
        total_chunks: Total chunks in job
        failure_reasons: List of (failure_type, message) tuples
        attempts: List of attempt details
    """
    topic_arn = AUDIO_VALIDATION_CONFIG.get('SNS_ALERT_TOPIC_ARN')
    if not topic_arn:
        logger.warning("No SNS topic configured for validation alerts")
        return

    try:
        message = {
            'alert_type': 'TTS_VALIDATION_FAILURE',
            'job_id': job_id,
            'chunk_index': chunk_index,
            'total_chunks': total_chunks,
            'failure_reasons': [{'type': t, 'message': m} for t, m in failure_reasons],
            'attempts': attempts,
            'timestamp': datetime.now().isoformat()
        }

        sns_client.publish(
            TopicArn=topic_arn,
            Subject=f"TTS Validation Failed: {job_id} chunk {chunk_index}",
            Message=json.dumps(message, indent=2)
        )
        logger.info(f"Sent validation failure alert for chunk {chunk_index}")
    except Exception as e:
        logger.error(f"Failed to send SNS alert: {str(e)}")


def generate_and_validate_audio(text: str, chunk_index: int, total_chunks: int,
                                job_id: str,
                                voice_name: str = None,
                                model: str = None) -> bytes:
    """
    Generate TTS audio with comprehensive validation and intelligent retry.

    Args:
        text: Text to convert to speech
        chunk_index: Current chunk index
        total_chunks: Total number of chunks
        job_id: Job identifier for alerting
        voice_name: TTS voice (defaults to TTS_VOICE)
        model: TTS model (defaults to TTS_MODEL)

    Returns:
        Validated WAV audio bytes

    Raises:
        AudioValidationError: If validation fails after all retries
    """
    voice_name = voice_name or TTS_VOICE
    model = model or TTS_MODEL

    max_attempts = AUDIO_VALIDATION_CONFIG['MAX_VALIDATION_RETRIES']
    validation_attempts = []
    last_validation_result = None

    for attempt in range(max_attempts):
        try:
            # Generate audio with existing retry logic (for API errors)
            pcm_data = generate_speech_with_retry(text, voice_name, model)
            wav_data = convert_pcm_to_wav(pcm_data)

            # Validate the output
            validation_result = validate_audio_output(
                wav_data, text, chunk_index, total_chunks
            )
            last_validation_result = validation_result

            # Log validation metrics
            log_validation_metrics(validation_result, attempt)

            validation_attempts.append({
                'attempt': attempt + 1,
                'valid': validation_result['valid'],
                'failure_reasons': validation_result['failure_reasons'],
                'duration_ratio': validation_result['duration'].get('ratio', 0),
                'size_ratio': validation_result['size'].get('ratio', 0),
                'silence_ratio': validation_result['silence'].get('silence_ratio', 0),
                'timestamp': datetime.now().isoformat()
            })

            if validation_result['valid']:
                logger.info(
                    f"Audio validation passed on attempt {attempt + 1}: "
                    f"duration={validation_result['duration'].get('actual_duration', 0):.1f}s, "
                    f"silence_ratio={validation_result['silence'].get('silence_ratio', 0):.1%}"
                )
                return wav_data

            # Determine if we should retry
            should_retry, delay, reason = ValidationRetryStrategy.should_retry(
                validation_result['failure_reasons'], attempt
            )

            if not should_retry:
                logger.error(f"Validation failed, not retrying: {reason}")
                break

            logger.warning(
                f"Validation failed (attempt {attempt + 1}/{max_attempts}): {reason}. "
                f"Waiting {delay:.1f}s before retry..."
            )

            for failure_type, message in validation_result['failure_reasons']:
                logger.warning(f"  - {failure_type}: {message}")

            time.sleep(delay)

        except AudioValidationError:
            raise
        except Exception as e:
            logger.error(f"Error during audio generation attempt {attempt + 1}: {str(e)}")
            validation_attempts.append({
                'attempt': attempt + 1,
                'error': str(e),
                'timestamp': datetime.now().isoformat()
            })
            if attempt < max_attempts - 1:
                time.sleep(AUDIO_VALIDATION_CONFIG['VALIDATION_RETRY_DELAY'])

    # All attempts failed - send alert and raise
    failure_reasons = last_validation_result['failure_reasons'] if last_validation_result else []
    send_validation_alert(job_id, chunk_index, total_chunks, failure_reasons, validation_attempts)

    raise AudioValidationError(
        f"Audio validation failed after {max_attempts} attempts for chunk {chunk_index}. "
        f"Failures: {failure_reasons}"
    )


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

    # Prepend instruction for Spanish reading
    text = "Read this in spanish:\\n" + text

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

                # Step 3: Generate and validate TTS audio
                # Uses comprehensive validation with intelligent retry
                try:
                    wav_data = generate_and_validate_audio(
                        text=chunk_text,
                        chunk_index=chunk_index,
                        total_chunks=total_chunks,
                        job_id=job_id
                    )
                except AudioValidationError as e:
                    # Validation failed after all retries - alert already sent
                    logger.error(
                        f"Audio validation failed for chunk {chunk_index+1}/{total_chunks}: {str(e)}"
                    )
                    raise
                except Exception as e:
                    # API or other error
                    logger.error(
                        f"Failed to generate TTS for chunk {chunk_index+1}/{total_chunks}: {str(e)}"
                    )
                    raise

                logger.info(f"Generated and validated {len(wav_data)} bytes of WAV audio")

                # Step 5: Store audio chunk in S3
                audio_key = store_audio_chunk(
                    job_id, chunk_index,
                    wav_data, total_chunks
                )

                # Step 6: Send to stitching queue
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