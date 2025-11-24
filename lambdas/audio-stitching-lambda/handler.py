#!/usr/bin/env python3
"""
Audio Stitching Lambda
Monitors completed audio chunks and stitches them into complete chapter audio files
"""

import json
import boto3
import os
import wave
import io
from typing import Dict, Any, List, Optional
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
dynamodb = boto3.resource('dynamodb')

# Environment variables
AUDIO_CHUNKS_BUCKET = os.environ.get('AUDIO_CHUNKS_BUCKET', 'audio-chunks-bucket272765753210')
FINAL_AUDIO_BUCKET = os.environ.get('PROCESSED_BUCKET_NAME', 'processed-file-bucket272765753210')
TRACKING_TABLE = os.environ.get('TRACKING_TABLE', 'AudioChunkTracking')

# Audio format configuration
SAMPLE_RATE = 24000
CHANNELS = 1
SAMPLE_WIDTH = 2

# Crossfade configuration
CROSSFADE_DURATION_MS = 50  # milliseconds


def get_tracking_table():
    """Get DynamoDB tracking table"""
    return dynamodb.Table(TRACKING_TABLE)


def update_chunk_status(job_id: str, chapter_num: int, chunk_index: int, total_chunks: int = None) -> bool:
    """
    Update chunk status in DynamoDB and check if all chunks are complete

    Args:
        job_id: Job identifier
        chapter_num: Chapter number
        chunk_index: Index of completed chunk
        total_chunks: Total number of chunks expected (optional, will be set on first chunk)

    Returns:
        True if all chunks for this chapter are complete
    """
    table = get_tracking_table()

    # Create composite key for the chapter
    chapter_key = f"{job_id}#chapter_{chapter_num:02d}"

    try:
        # Build update expression
        update_expr = 'SET chunks_completed = list_append(if_not_exists(chunks_completed, :empty), :chunk)'
        expr_values = {
            ':empty': [],
            ':chunk': [chunk_index]
        }

        # Also set total_chunks if provided
        if total_chunks is not None:
            update_expr += ', total_chunks = if_not_exists(total_chunks, :total)'
            expr_values[':total'] = total_chunks

        # Update chunk status
        response = table.update_item(
            Key={'chapter_key': chapter_key},
            UpdateExpression=update_expr,
            ExpressionAttributeValues=expr_values,
            ReturnValues='ALL_NEW'
        )

        # Check if all chunks are complete
        item = response['Attributes']
        completed_chunks = set(item.get('chunks_completed', []))
        stored_total = item.get('total_chunks')

        if stored_total and len(completed_chunks) >= stored_total:
            logger.info(f"All {stored_total} chunks completed for Chapter {chapter_num}")
            return True

        logger.info(f"Chapter {chapter_num}: {len(completed_chunks)}/{stored_total} chunks completed")
        return False

    except Exception as e:
        logger.error(f"Error updating chunk status: {str(e)}")
        return False


def download_audio_chunk(bucket: str, key: str) -> bytes:
    """
    Download audio chunk from S3

    Args:
        bucket: S3 bucket name
        key: S3 object key

    Returns:
        WAV audio data as bytes
    """
    logger.info(f"Downloading audio chunk from s3://{bucket}/{key}")

    response = s3_client.get_object(Bucket=bucket, Key=key)
    audio_data = response['Body'].read()

    logger.info(f"Downloaded {len(audio_data)} bytes of audio")
    return audio_data


def download_chunks_parallel(job_id: str, chapter_num: int,
                            total_chunks: int) -> List[bytes]:
    """
    Download all audio chunks for a chapter in parallel

    Returns:
        List of WAV audio data in order
    """
    chunks = [None] * total_chunks

    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = {}

        for i in range(total_chunks):
            chunk_key = f"{job_id}/chapter_{chapter_num:02d}/audio_chunk_{i:03d}.wav"
            future = executor.submit(download_audio_chunk, AUDIO_CHUNKS_BUCKET, chunk_key)
            futures[future] = i

        for future in as_completed(futures):
            chunk_index = futures[future]
            try:
                chunks[chunk_index] = future.result()
                logger.info(f"Downloaded chunk {chunk_index+1}/{total_chunks}")
            except Exception as e:
                logger.error(f"Failed to download chunk {chunk_index}: {str(e)}")
                raise

    return chunks


def create_stitched_audio_streaming(job_id: str, chapter_num: int,
                                   total_chunks: int) -> str:
    """
    Stream-process audio chunks to reduce memory usage

    Args:
        job_id: Job identifier
        chapter_num: Chapter number
        total_chunks: Total number of chunks to process

    Returns:
        Path to temporary file containing stitched audio
    """
    import tempfile
    import struct

    # Create temporary file for output
    temp_file = tempfile.NamedTemporaryFile(suffix='.wav', delete=False)
    temp_path = temp_file.name
    temp_file.close()

    logger.info(f"Starting streaming audio stitching for {total_chunks} chunks to {temp_path}")

    # Calculate crossfade samples
    crossfade_samples = int((CROSSFADE_DURATION_MS / 1000) * SAMPLE_RATE)
    logger.info(f"Using {crossfade_samples} samples for crossfading ({CROSSFADE_DURATION_MS}ms)")

    # Process chunks sequentially
    previous_overlap = None  # Store last crossfade_samples from previous chunk

    with wave.open(temp_path, 'wb') as output_wav:
        output_wav.setnchannels(CHANNELS)
        output_wav.setsampwidth(SAMPLE_WIDTH)
        output_wav.setframerate(SAMPLE_RATE)

        for chunk_index in range(total_chunks):
            # Download single chunk
            chunk_key = f"{job_id}/chapter_{chapter_num:02d}/audio_chunk_{chunk_index:03d}.wav"
            logger.info(f"Processing chunk {chunk_index+1}/{total_chunks}: {chunk_key}")

            chunk_data = download_audio_chunk(AUDIO_CHUNKS_BUCKET, chunk_key)

            # Extract frames from this chunk
            wav_buffer = io.BytesIO(chunk_data)
            with wave.open(wav_buffer, 'rb') as wav_file:
                frames = wav_file.readframes(wav_file.getnframes())

            # Apply crossfade if not first chunk
            if chunk_index == 0:
                # First chunk - write most of it directly
                if total_chunks > 1 and len(frames) > crossfade_samples * SAMPLE_WIDTH:
                    # Write all but the last crossfade_samples
                    samples_to_write = len(frames) - (crossfade_samples * SAMPLE_WIDTH)
                    output_wav.writeframes(frames[:samples_to_write])
                    # Save overlap for next iteration
                    previous_overlap = frames[samples_to_write:]
                else:
                    # Only one chunk or too small to crossfade
                    output_wav.writeframes(frames)
                    previous_overlap = None
            else:
                # Apply crossfade with previous overlap
                if previous_overlap:
                    crossfaded = apply_crossfade_streaming(previous_overlap, frames, crossfade_samples)
                    output_wav.writeframes(crossfaded)
                else:
                    # No overlap available, just write the frames
                    output_wav.writeframes(frames)

                # Save overlap for next iteration if not last chunk
                if chunk_index < total_chunks - 1 and len(frames) > crossfade_samples * SAMPLE_WIDTH:
                    # Keep last crossfade_samples for next iteration
                    samples_to_keep = crossfade_samples * SAMPLE_WIDTH
                    previous_overlap = frames[-samples_to_keep:]
                else:
                    previous_overlap = None

            # Free memory
            del chunk_data
            del frames
            logger.info(f"Completed processing chunk {chunk_index+1}/{total_chunks}")

    logger.info(f"Successfully stitched {total_chunks} chunks to {temp_path}")
    return temp_path


def apply_crossfade_streaming(overlap_frames: bytes, new_frames: bytes,
                             crossfade_samples: int) -> bytes:
    """
    Apply crossfade and return the combined audio

    Args:
        overlap_frames: Last samples from previous chunk (for crossfading)
        new_frames: Complete new chunk
        crossfade_samples: Number of samples to crossfade

    Returns:
        Crossfaded audio combining overlap with new frames
    """
    import struct

    # Convert bytes to samples (16-bit signed integers)
    overlap_samples = list(struct.unpack(f'{len(overlap_frames)//2}h', overlap_frames))
    new_samples = list(struct.unpack(f'{len(new_frames)//2}h', new_frames))

    if len(overlap_samples) < crossfade_samples or len(new_samples) < crossfade_samples:
        logger.warning(f"Not enough samples for crossfade: overlap={len(overlap_samples)}, new={len(new_samples)}, required={crossfade_samples}")
        # Not enough samples for crossfade, just return new frames
        return new_frames

    result = []

    # Create crossfaded transition
    for i in range(crossfade_samples):
        # Linear crossfade
        weight1 = 1.0 - (i / crossfade_samples)
        weight2 = i / crossfade_samples

        sample1 = overlap_samples[i]
        sample2 = new_samples[i]

        # Mix the samples
        mixed = int(sample1 * weight1 + sample2 * weight2)
        # Clamp to 16-bit range
        mixed = max(-32768, min(32767, mixed))
        result.append(mixed)

    # Add remaining samples from new chunk (after crossfade region)
    result.extend(new_samples[crossfade_samples:])

    # Convert back to bytes
    return struct.pack(f'{len(result)}h', *result)


def apply_crossfade(frames1: bytes, frames2: bytes,
                    crossfade_samples: int) -> bytes:
    """
    Apply crossfade between two audio segments

    Args:
        frames1: First audio segment (bytes)
        frames2: Second audio segment (bytes)
        crossfade_samples: Number of samples to crossfade

    Returns:
        Crossfaded audio bytes
    """
    import struct

    # Convert bytes to samples (16-bit signed integers)
    samples1 = list(struct.unpack(f'{len(frames1)//2}h', frames1))
    samples2 = list(struct.unpack(f'{len(frames2)//2}h', frames2))

    if len(samples1) < crossfade_samples or len(samples2) < crossfade_samples:
        # Not enough samples for crossfade, just concatenate
        return frames1 + frames2

    # Create crossfaded transition
    result = samples1[:-crossfade_samples]  # Keep all but last crossfade_samples from first

    # Crossfade region
    for i in range(crossfade_samples):
        # Linear crossfade
        weight1 = 1.0 - (i / crossfade_samples)
        weight2 = i / crossfade_samples

        sample1 = samples1[-(crossfade_samples - i)]
        sample2 = samples2[i]

        # Mix the samples
        mixed = int(sample1 * weight1 + sample2 * weight2)
        # Clamp to 16-bit range
        mixed = max(-32768, min(32767, mixed))
        result.append(mixed)

    # Add remaining samples from second segment
    result.extend(samples2[crossfade_samples:])

    # Convert back to bytes
    return struct.pack(f'{len(result)}h', *result)


def concatenate_wav_with_crossfade(wav_chunks: List[bytes]) -> bytes:
    """
    Concatenate multiple WAV files with crossfading between chunks

    Args:
        wav_chunks: List of WAV file data as bytes

    Returns:
        Concatenated WAV file as bytes
    """
    if not wav_chunks:
        raise ValueError("No WAV chunks to concatenate")

    if len(wav_chunks) == 1:
        return wav_chunks[0]

    logger.info(f"Concatenating {len(wav_chunks)} WAV chunks with crossfading")

    # Calculate crossfade samples
    crossfade_samples = int((CROSSFADE_DURATION_MS / 1000) * SAMPLE_RATE)
    logger.info(f"Using {crossfade_samples} samples for crossfading ({CROSSFADE_DURATION_MS}ms)")

    # Extract audio frames from all WAV files
    all_frames = []
    for i, wav_data in enumerate(wav_chunks):
        wav_buffer = io.BytesIO(wav_data)
        with wave.open(wav_buffer, 'rb') as wav_file:
            frames = wav_file.readframes(wav_file.getnframes())
            all_frames.append(frames)
            logger.info(f"Chunk {i+1}: {len(frames)} bytes of audio data")

    # Apply crossfading between chunks
    concatenated_frames = all_frames[0]

    for i in range(1, len(all_frames)):
        logger.info(f"Applying crossfade between chunk {i} and {i+1}")
        concatenated_frames = apply_crossfade(
            concatenated_frames, all_frames[i], crossfade_samples
        )

    # Create final WAV file
    output_buffer = io.BytesIO()
    with wave.open(output_buffer, 'wb') as output_wav:
        output_wav.setnchannels(CHANNELS)
        output_wav.setsampwidth(SAMPLE_WIDTH)
        output_wav.setframerate(SAMPLE_RATE)
        output_wav.writeframes(concatenated_frames)

    result = output_buffer.getvalue()
    logger.info(f"Final concatenated WAV size: {len(result)} bytes")

    return result


def store_final_audio(job_id: str, chapter_num: int, chapter_title: str,
                     wav_data: bytes) -> str:
    """
    Store final stitched audio in S3

    Returns:
        S3 key of stored audio
    """
    output_key = f"jobs/{job_id}/chapter_{chapter_num:02d}.wav"

    metadata = {
        'job_id': job_id,
        'chapter_number': str(chapter_num),
        'chapter_title': chapter_title,
        'audio_size': str(len(wav_data)),
        'sample_rate': str(SAMPLE_RATE),
        'channels': str(CHANNELS),
        'processing': 'stitched_with_crossfade'
    }

    logger.info(f"Storing final audio to s3://{FINAL_AUDIO_BUCKET}/{output_key}")

    s3_client.put_object(
        Bucket=FINAL_AUDIO_BUCKET,
        Key=output_key,
        Body=wav_data,
        ContentType='audio/wav',
        Metadata=metadata
    )

    return output_key


def cleanup_chunks(job_id: str, chapter_num: int, total_chunks: int):
    """
    Clean up intermediate chunk files after successful stitching
    """
    logger.info(f"Cleaning up {total_chunks} chunk files")

    try:
        # Delete audio chunks
        audio_keys = []
        for i in range(total_chunks):
            audio_keys.append({
                'Key': f"{job_id}/chapter_{chapter_num:02d}/audio_chunk_{i:03d}.wav"
            })

        if audio_keys:
            s3_client.delete_objects(
                Bucket=AUDIO_CHUNKS_BUCKET,
                Delete={'Objects': audio_keys}
            )
            logger.info(f"Deleted {len(audio_keys)} audio chunk files")

    except Exception as e:
        logger.warning(f"Error during cleanup (non-critical): {str(e)}")


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for audio stitching
    Triggered by completion messages from TTS generation lambda

    Can be triggered by:
    1. SQS messages for individual chunk completions
    2. Direct invocation to stitch a specific chapter

    SQS Message format:
    {
        "job_id": "book-123",
        "chapter": {
            "number": 1,
            "title": "Introduction"
        },
        "chunk": {
            "index": 0,
            "total": 5,
            "audio_s3_key": "job-123/chapter_01/audio_chunk_000.wav",
            "audio_bucket": "audio-chunks-bucket"
        }
    }
    """
    try:
        # Check if this is an SQS event or direct invocation
        records = event.get('Records', [])

        if records:
            # Process SQS records
            results = []

            for record in records:
                try:
                    message_body = json.loads(record['body'])

                    job_id = message_body['job_id']
                    chapter_info = message_body['chapter']
                    chunk_info = message_body['chunk']

                    chapter_num = chapter_info['number']
                    chapter_title = chapter_info['title']
                    chunk_index = chunk_info['index']
                    total_chunks = chunk_info['total']

                    logger.info(
                        f"Chunk completion notification: Job {job_id}, Chapter {chapter_num}, "
                        f"Chunk {chunk_index+1}/{total_chunks}"
                    )

                    # Update tracking and check if all chunks are complete
                    all_complete = update_chunk_status(job_id, chapter_num, chunk_index, total_chunks)

                    if all_complete:
                        logger.info(f"All chunks complete for Chapter {chapter_num}, starting stitching")

                        # Use streaming approach to reduce memory usage
                        import os
                        temp_audio_path = create_stitched_audio_streaming(job_id, chapter_num, total_chunks)

                        try:
                            # Read final audio from temp file for upload
                            with open(temp_audio_path, 'rb') as f:
                                final_wav = f.read()

                            # Store final audio
                            final_key = store_final_audio(
                                job_id, chapter_num, chapter_title, final_wav
                            )

                            # Clean up intermediate files
                            cleanup_chunks(job_id, chapter_num, total_chunks)

                            results.append({
                                'job_id': job_id,
                                'chapter_number': chapter_num,
                                'chapter_title': chapter_title,
                                'status': 'stitched',
                                'output_key': final_key,
                                'audio_size': len(final_wav),
                                's3_location': f's3://{FINAL_AUDIO_BUCKET}/{final_key}'
                            })

                            logger.info(f"Successfully stitched Chapter {chapter_num}: {final_key}")

                        finally:
                            # Clean up temp file
                            if os.path.exists(temp_audio_path):
                                os.remove(temp_audio_path)
                                logger.info(f"Cleaned up temporary file: {temp_audio_path}")
                    else:
                        results.append({
                            'job_id': job_id,
                            'chapter_number': chapter_num,
                            'chunk_index': chunk_index,
                            'status': 'chunk_recorded'
                        })

                except Exception as e:
                    logger.error(f"Error processing SQS record: {str(e)}", exc_info=True)
                    results.append({
                        'error': str(e),
                        'record': record.get('messageId', 'unknown')
                    })

            return {
                'statusCode': 200,
                'body': json.dumps({
                    'message': f'Processed {len(results)} notifications',
                    'results': results
                })
            }

        else:
            # Direct invocation to force stitching
            job_id = event.get('job_id')
            chapter_num = event.get('chapter_number')
            total_chunks = event.get('total_chunks')
            chapter_title = event.get('chapter_title', f'Chapter {chapter_num}')

            if not all([job_id, chapter_num, total_chunks]):
                return {
                    'statusCode': 400,
                    'body': json.dumps({
                        'error': 'Direct invocation requires job_id, chapter_number, and total_chunks'
                    })
                }

            logger.info(f"Direct stitching request for Chapter {chapter_num}")

            # Use streaming approach to reduce memory usage
            import os
            temp_audio_path = create_stitched_audio_streaming(job_id, chapter_num, total_chunks)

            try:
                # Read final audio from temp file for upload
                with open(temp_audio_path, 'rb') as f:
                    final_wav = f.read()

                # Store final audio
                final_key = store_final_audio(job_id, chapter_num, chapter_title, final_wav)

                return {
                    'statusCode': 200,
                    'body': json.dumps({
                        'message': 'Chapter stitched successfully',
                        'output_key': final_key,
                        'audio_size': len(final_wav),
                        's3_location': f's3://{FINAL_AUDIO_BUCKET}/{final_key}'
                    })
                }

            finally:
                # Clean up temp file
                if os.path.exists(temp_audio_path):
                    os.remove(temp_audio_path)
                    logger.info(f"Cleaned up temporary file: {temp_audio_path}")

    except Exception as e:
        logger.error(f"Fatal error in lambda handler: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }