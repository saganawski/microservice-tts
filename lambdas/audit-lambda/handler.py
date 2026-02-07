#!/usr/bin/env python3
"""
Audio Quality Audit Lambda

Transcribes generated audio using OpenAI Whisper API and compares
against source text to calculate Word Error Rate (WER).

Triggers: 
  - SQS message from AuditQueue (after job completion)
  - Manual invocation with job_id

Process:
  1. Retrieve job metadata and chunk list
  2. For each chunk:
     a. Download audio from AudioChunksBucket
     b. Transcribe using OpenAI Whisper API
     c. Download source text from TextChunksBucket
     d. Calculate WER using jiwer
  3. Store per-chunk and aggregate results
  4. Send notification with audit summary
"""

import json
import os
import boto3
import tempfile
from datetime import datetime
from typing import Dict, Any, List, Optional
import logging

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize AWS clients
s3_client = boto3.client('s3')
sns_client = boto3.client('sns')

# Environment variables
TEXT_CHUNKS_BUCKET = os.environ.get('TEXT_CHUNKS_BUCKET', 'text-chunks-bucket272765753210')
AUDIO_CHUNKS_BUCKET = os.environ.get('AUDIO_CHUNKS_BUCKET', 'audio-chunks-bucket272765753210')
AUDIT_RESULTS_BUCKET = os.environ.get('AUDIT_RESULTS_BUCKET', 'audit-results-bucket272765753210')
AUDIT_SNS_TOPIC_ARN = os.environ.get('AUDIT_SNS_TOPIC_ARN', '')
OPENAI_API_KEY = os.environ.get('OPENAI_API_KEY', '')  # Stub - to be filled in

# Whisper API configuration
WHISPER_MODEL = os.environ.get('WHISPER_MODEL', 'whisper-1')

# Import optional dependencies
try:
    from openai import OpenAI
    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False
    logger.warning("OpenAI library not available")

try:
    from jiwer import wer, cer, wer_standardize
    JIWER_AVAILABLE = True
except ImportError:
    JIWER_AVAILABLE = False
    logger.warning("jiwer library not available - WER calculation disabled")


def normalize_text(text: str) -> str:
    """
    Normalize text for fair comparison.
    - Lowercase
    - Remove extra whitespace
    - Remove punctuation (optional, configurable)
    """
    import re
    
    # Lowercase
    text = text.lower()
    
    # Remove extra whitespace
    text = ' '.join(text.split())
    
    # Optionally remove punctuation for more lenient comparison
    # text = re.sub(r'[^\w\s]', '', text)
    
    return text.strip()


def transcribe_audio_whisper(audio_path: str) -> Dict[str, Any]:
    """
    Transcribe audio file using OpenAI Whisper API.
    
    Args:
        audio_path: Path to local audio file
        
    Returns:
        Dict with transcription text and metadata
    """
    if not OPENAI_AVAILABLE:
        raise RuntimeError("OpenAI library not installed")
    
    if not OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY not configured")
    
    client = OpenAI(api_key=OPENAI_API_KEY)
    
    logger.info(f"Transcribing audio: {audio_path}")
    
    with open(audio_path, 'rb') as audio_file:
        # Get file size for cost estimation
        audio_file.seek(0, 2)
        file_size = audio_file.tell()
        audio_file.seek(0)
        
        response = client.audio.transcriptions.create(
            model=WHISPER_MODEL,
            file=audio_file,
            response_format="verbose_json",
            language="en"  # Specify language for better accuracy
        )
    
    # Extract duration if available
    duration_seconds = getattr(response, 'duration', None)
    
    return {
        'text': response.text,
        'duration_seconds': duration_seconds,
        'file_size_bytes': file_size,
        'model': WHISPER_MODEL
    }


def calculate_wer_metrics(reference: str, hypothesis: str) -> Dict[str, float]:
    """
    Calculate Word Error Rate and related metrics.
    
    Args:
        reference: Original/expected text
        hypothesis: Transcribed text
        
    Returns:
        Dict with WER, CER, and other metrics
    """
    if not JIWER_AVAILABLE:
        # Fallback: simple word match percentage
        ref_words = normalize_text(reference).split()
        hyp_words = normalize_text(hypothesis).split()
        
        if not ref_words:
            return {'wer': 0.0, 'method': 'fallback', 'warning': 'empty reference'}
        
        # Simple Levenshtein-like approximation
        matches = sum(1 for r, h in zip(ref_words, hyp_words) if r == h)
        simple_accuracy = matches / max(len(ref_words), len(hyp_words))
        
        return {
            'wer': 1.0 - simple_accuracy,
            'accuracy': simple_accuracy,
            'method': 'fallback',
            'warning': 'jiwer not available - using simple comparison'
        }
    
    # Normalize texts
    ref_normalized = normalize_text(reference)
    hyp_normalized = normalize_text(hypothesis)
    
    # Calculate metrics
    word_error_rate = wer(ref_normalized, hyp_normalized)
    char_error_rate = cer(ref_normalized, hyp_normalized)
    
    # Additional stats
    ref_word_count = len(ref_normalized.split())
    hyp_word_count = len(hyp_normalized.split())
    
    return {
        'wer': round(word_error_rate, 4),
        'cer': round(char_error_rate, 4),
        'accuracy': round(1.0 - word_error_rate, 4),
        'reference_word_count': ref_word_count,
        'hypothesis_word_count': hyp_word_count,
        'word_count_diff': hyp_word_count - ref_word_count,
        'method': 'jiwer'
    }


def get_job_chunks(job_id: str) -> List[int]:
    """
    Get list of chunk indices for a job from S3.
    """
    # List objects in the text chunks bucket for this job
    response = s3_client.list_objects_v2(
        Bucket=TEXT_CHUNKS_BUCKET,
        Prefix=f"{job_id}/chunk_"
    )
    
    chunks = []
    for obj in response.get('Contents', []):
        key = obj['Key']
        # Extract chunk index from key like "job_id/chunk_0001.txt"
        if key.endswith('.txt'):
            try:
                chunk_part = key.split('/')[-1]  # chunk_0001.txt
                chunk_index = int(chunk_part.replace('chunk_', '').replace('.txt', ''))
                chunks.append(chunk_index)
            except ValueError:
                continue
    
    return sorted(chunks)


def download_s3_file(bucket: str, key: str, local_path: str) -> None:
    """Download file from S3 to local path."""
    logger.info(f"Downloading s3://{bucket}/{key} to {local_path}")
    s3_client.download_file(bucket, key, local_path)


def get_text_from_s3(bucket: str, key: str) -> str:
    """Get text content from S3."""
    response = s3_client.get_object(Bucket=bucket, Key=key)
    return response['Body'].read().decode('utf-8')


def audit_chunk(job_id: str, chunk_index: int) -> Dict[str, Any]:
    """
    Audit a single chunk: transcribe and compare.
    
    Returns:
        Audit result for this chunk
    """
    chunk_key = f"chunk_{chunk_index:04d}"
    text_key = f"{job_id}/{chunk_key}.txt"
    audio_key = f"{job_id}/{chunk_key}.wav"
    
    result = {
        'chunk_index': chunk_index,
        'text_key': text_key,
        'audio_key': audio_key,
        'status': 'pending'
    }
    
    try:
        # Get source text
        logger.info(f"Fetching source text: {text_key}")
        source_text = get_text_from_s3(TEXT_CHUNKS_BUCKET, text_key)
        result['source_text_length'] = len(source_text)
        result['source_word_count'] = len(source_text.split())
        
        # Download audio to temp file
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp_audio:
            tmp_audio_path = tmp_audio.name
        
        try:
            download_s3_file(AUDIO_CHUNKS_BUCKET, audio_key, tmp_audio_path)
            
            # Transcribe audio
            transcription = transcribe_audio_whisper(tmp_audio_path)
            result['transcription'] = transcription['text']
            result['audio_duration_seconds'] = transcription.get('duration_seconds')
            result['audio_file_size'] = transcription.get('file_size_bytes')
            
            # Calculate WER
            metrics = calculate_wer_metrics(source_text, transcription['text'])
            result['metrics'] = metrics
            result['status'] = 'completed'
            
            # Quality assessment
            wer_value = metrics.get('wer', 1.0)
            if wer_value <= 0.05:
                result['quality'] = 'excellent'
            elif wer_value <= 0.10:
                result['quality'] = 'good'
            elif wer_value <= 0.20:
                result['quality'] = 'acceptable'
            else:
                result['quality'] = 'poor'
                
        finally:
            # Clean up temp file
            if os.path.exists(tmp_audio_path):
                os.remove(tmp_audio_path)
                
    except Exception as e:
        logger.error(f"Error auditing chunk {chunk_index}: {str(e)}")
        result['status'] = 'error'
        result['error'] = str(e)
    
    return result


def store_audit_results(job_id: str, results: Dict[str, Any]) -> str:
    """
    Store audit results to S3.
    
    Returns:
        S3 key of stored results
    """
    results_key = f"{job_id}/audit_results.json"
    
    logger.info(f"Storing audit results to s3://{AUDIT_RESULTS_BUCKET}/{results_key}")
    
    s3_client.put_object(
        Bucket=AUDIT_RESULTS_BUCKET,
        Key=results_key,
        Body=json.dumps(results, indent=2, default=str),
        ContentType='application/json'
    )
    
    return results_key


def send_audit_notification(job_id: str, summary: Dict[str, Any]) -> None:
    """Send SNS notification with audit summary."""
    if not AUDIT_SNS_TOPIC_ARN:
        logger.info("No audit SNS topic configured, skipping notification")
        return
    
    quality = summary.get('overall_quality', 'unknown')
    avg_wer = summary.get('average_wer', 'N/A')
    total_chunks = summary.get('total_chunks', 0)
    
    subject = f"TTS Audit Complete: {job_id} - {quality.upper()}"
    
    message = f"""
TTS Audio Quality Audit Report
==============================

Job ID: {job_id}
Completed: {datetime.now().isoformat()}

Summary
-------
Total Chunks: {total_chunks}
Successful: {summary.get('successful_chunks', 0)}
Failed: {summary.get('failed_chunks', 0)}

Quality Metrics
---------------
Average WER: {avg_wer:.2%} if isinstance(avg_wer, float) else avg_wer
Average CER: {summary.get('average_cer', 'N/A'):.2%} if isinstance(summary.get('average_cer'), float) else 'N/A'
Overall Quality: {quality}

Quality Distribution:
- Excellent (WER ≤ 5%): {summary.get('quality_distribution', {}).get('excellent', 0)}
- Good (WER ≤ 10%): {summary.get('quality_distribution', {}).get('good', 0)}
- Acceptable (WER ≤ 20%): {summary.get('quality_distribution', {}).get('acceptable', 0)}
- Poor (WER > 20%): {summary.get('quality_distribution', {}).get('poor', 0)}

Estimated Cost
--------------
Audio Duration: {summary.get('total_duration_minutes', 0):.1f} minutes
Whisper API Cost: ${summary.get('estimated_cost', 0):.2f}

Results stored at: s3://{AUDIT_RESULTS_BUCKET}/{job_id}/audit_results.json
"""
    
    sns_client.publish(
        TopicArn=AUDIT_SNS_TOPIC_ARN,
        Subject=subject,
        Message=message
    )
    
    logger.info(f"Sent audit notification to {AUDIT_SNS_TOPIC_ARN}")


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Main Lambda handler for audio quality auditing.
    
    Event formats:
    1. Direct invocation: {"job_id": "xxx"}
    2. SQS trigger: {"Records": [{"body": "{\"job_id\": \"xxx\"}"}]}
    
    Optional parameters:
    - chunk_indices: List of specific chunks to audit (default: all)
    - skip_notification: Don't send SNS notification
    """
    logger.info(f"Audit Lambda invoked with event: {json.dumps(event)}")
    
    # Check dependencies
    if not OPENAI_AVAILABLE:
        return {
            'statusCode': 500,
            'body': json.dumps({
                'error': 'OpenAI library not installed',
                'hint': 'Add openai to requirements.txt'
            })
        }
    
    if not OPENAI_API_KEY:
        return {
            'statusCode': 500,
            'body': json.dumps({
                'error': 'OPENAI_API_KEY not configured',
                'hint': 'Set OPENAI_API_KEY environment variable'
            })
        }
    
    # Parse event (handle both direct and SQS invocation)
    if 'Records' in event:
        # SQS trigger
        message = json.loads(event['Records'][0]['body'])
    else:
        # Direct invocation
        message = event
    
    job_id = message.get('job_id')
    if not job_id:
        return {
            'statusCode': 400,
            'body': json.dumps({'error': 'job_id is required'})
        }
    
    chunk_indices = message.get('chunk_indices')  # Optional: specific chunks
    skip_notification = message.get('skip_notification', False)
    
    logger.info(f"Starting audit for job: {job_id}")
    
    try:
        # Get chunk list
        if chunk_indices is None:
            chunk_indices = get_job_chunks(job_id)
        
        if not chunk_indices:
            return {
                'statusCode': 404,
                'body': json.dumps({
                    'error': f'No chunks found for job {job_id}',
                    'bucket': TEXT_CHUNKS_BUCKET
                })
            }
        
        logger.info(f"Auditing {len(chunk_indices)} chunks for job {job_id}")
        
        # Audit each chunk
        chunk_results = []
        total_duration = 0
        total_wer = 0
        total_cer = 0
        successful_count = 0
        quality_distribution = {'excellent': 0, 'good': 0, 'acceptable': 0, 'poor': 0}
        
        for chunk_index in chunk_indices:
            logger.info(f"Auditing chunk {chunk_index + 1}/{len(chunk_indices)}")
            
            chunk_result = audit_chunk(job_id, chunk_index)
            chunk_results.append(chunk_result)
            
            if chunk_result['status'] == 'completed':
                successful_count += 1
                metrics = chunk_result.get('metrics', {})
                total_wer += metrics.get('wer', 0)
                total_cer += metrics.get('cer', 0)
                
                duration = chunk_result.get('audio_duration_seconds', 0)
                if duration:
                    total_duration += duration
                
                quality = chunk_result.get('quality', 'poor')
                quality_distribution[quality] = quality_distribution.get(quality, 0) + 1
        
        # Calculate summary
        avg_wer = total_wer / successful_count if successful_count > 0 else None
        avg_cer = total_cer / successful_count if successful_count > 0 else None
        total_duration_minutes = total_duration / 60
        estimated_cost = total_duration_minutes * 0.006  # $0.006/minute
        
        # Determine overall quality
        if avg_wer is None:
            overall_quality = 'unknown'
        elif avg_wer <= 0.05:
            overall_quality = 'excellent'
        elif avg_wer <= 0.10:
            overall_quality = 'good'
        elif avg_wer <= 0.20:
            overall_quality = 'acceptable'
        else:
            overall_quality = 'poor'
        
        # Build summary
        summary = {
            'job_id': job_id,
            'audit_timestamp': datetime.now().isoformat(),
            'total_chunks': len(chunk_indices),
            'successful_chunks': successful_count,
            'failed_chunks': len(chunk_indices) - successful_count,
            'average_wer': avg_wer,
            'average_cer': avg_cer,
            'overall_quality': overall_quality,
            'quality_distribution': quality_distribution,
            'total_duration_minutes': total_duration_minutes,
            'estimated_cost': estimated_cost,
            'whisper_model': WHISPER_MODEL
        }
        
        # Full results
        results = {
            'summary': summary,
            'chunk_results': chunk_results
        }
        
        # Store results
        results_key = store_audit_results(job_id, results)
        results['results_s3_key'] = results_key
        
        # Send notification
        if not skip_notification:
            send_audit_notification(job_id, summary)
        
        logger.info(f"Audit complete for {job_id}: {overall_quality} (WER: {avg_wer})")
        
        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': 'Audit completed',
                'summary': summary,
                'results_location': f's3://{AUDIT_RESULTS_BUCKET}/{results_key}'
            }, default=str)
        }
        
    except Exception as e:
        logger.error(f"Audit failed for {job_id}: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({
                'error': str(e),
                'job_id': job_id
            })
        }
