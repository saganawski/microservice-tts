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


def update_chunk_status(job_id: str, chunk_index: int, total_chunks: int = None) -> bool:
    """
    Update chunk status in DynamoDB and check if all chunks are complete

    Changed from chapter-based to job-based tracking for whole-book processing

    Args:
        job_id: Job identifier
        chunk_index: Index of completed chunk
        total_chunks: Total number of chunks expected (optional, will be set on first chunk)

    Returns:
        True if all chunks for this job are complete
    """
    table = get_tracking_table()

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

        # Update chunk status (using job_id as primary key)
        response = table.update_item(
            Key={'job_id': job_id},
            UpdateExpression=update_expr,
            ExpressionAttributeValues=expr_values,
            ReturnValues='ALL_NEW'
        )

        # Check if all chunks are complete
        item = response['Attributes']
        completed_chunks = set(item.get('chunks_completed', []))
        stored_total = item.get('total_chunks')

        if stored_total and len(completed_chunks) >= stored_total:
            logger.info(f"All {stored_total} chunks completed for job {job_id}")
            return True

        logger.info(f"Job {job_id}: {len(completed_chunks)}/{stored_total} chunks completed")
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


def download_chunks_parallel(job_id: str, total_chunks: int) -> List[bytes]:
    """
    Download all audio chunks for a job in parallel

    Returns:
        List of WAV audio data in order
    """
    chunks = [None] * total_chunks

    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = {}

        for i in range(total_chunks):
            chunk_key = f"{job_id}/chunk_{i:04d}.wav"
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


def create_stitched_audio_streaming(job_id: str, chunk_indices: List[int]) -> str:
    """
    Stream-process audio chunks to reduce memory usage
    Changed to whole-book processing with duration-based segments

    Args:
        job_id: Job identifier
        chunk_indices: List of chunk indices to stitch together

    Returns:
        Path to temporary file containing stitched audio
    """
    import tempfile
    import struct

    # Create temporary file for output
    temp_file = tempfile.NamedTemporaryFile(suffix='.wav', delete=False)
    temp_path = temp_file.name
    temp_file.close()

    total_chunks = len(chunk_indices)
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

        for idx, chunk_index in enumerate(chunk_indices):
            # Download single chunk
            chunk_key = f"{job_id}/chunk_{chunk_index:04d}.wav"
            logger.info(f"Processing chunk {idx+1}/{total_chunks}: {chunk_key}")

            chunk_data = download_audio_chunk(AUDIO_CHUNKS_BUCKET, chunk_key)

            # Extract frames from this chunk
            wav_buffer = io.BytesIO(chunk_data)
            with wave.open(wav_buffer, 'rb') as wav_file:
                frames = wav_file.readframes(wav_file.getnframes())

            # Apply crossfade if not first chunk
            if idx == 0:
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
                if idx < total_chunks - 1 and len(frames) > crossfade_samples * SAMPLE_WIDTH:
                    # Keep last crossfade_samples for next iteration
                    samples_to_keep = crossfade_samples * SAMPLE_WIDTH
                    previous_overlap = frames[-samples_to_keep:]
                else:
                    previous_overlap = None

            # Free memory
            del chunk_data
            del frames
            logger.info(f"Completed processing chunk {idx+1}/{total_chunks}")

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


def store_final_audio(job_id: str, part_number: int, wav_data: bytes, total_parts: int = None) -> str:
    """
    Store final stitched audio in S3
    Changed to duration-based parts instead of chapters

    Returns:
        S3 key of stored audio
    """
    output_key = f"jobs/{job_id}/part_{part_number:02d}.wav"

    metadata = {
        'job_id': job_id,
        'part_number': str(part_number),
        'audio_size': str(len(wav_data)),
        'sample_rate': str(SAMPLE_RATE),
        'channels': str(CHANNELS),
        'processing': 'stitched_with_crossfade'
    }

    if total_parts:
        metadata['total_parts'] = str(total_parts)

    logger.info(f"Storing final audio to s3://{FINAL_AUDIO_BUCKET}/{output_key}")

    s3_client.put_object(
        Bucket=FINAL_AUDIO_BUCKET,
        Key=output_key,
        Body=wav_data,
        ContentType='audio/wav',
        Metadata=metadata
    )

    return output_key


def cleanup_chunks(job_id: str, chunk_indices: List[int]):
    """
    Clean up intermediate chunk files after successful stitching
    Changed to whole-book processing - removed chapter references
    """
    logger.info(f"Cleaning up {len(chunk_indices)} chunk files")

    try:
        # Delete audio chunks
        audio_keys = []
        for chunk_index in chunk_indices:
            audio_keys.append({
                'Key': f"{job_id}/chunk_{chunk_index:04d}.wav"
            })

        if audio_keys:
            s3_client.delete_objects(
                Bucket=AUDIO_CHUNKS_BUCKET,
                Delete={'Objects': audio_keys}
            )
            logger.info(f"Deleted {len(audio_keys)} audio chunk files")

    except Exception as e:
        logger.warning(f"Error during cleanup (non-critical): {str(e)}")


def calculate_duration_based_segments(total_chunks: int, target_duration_hours: float = 1.0) -> List[List[int]]:
    """
    Calculate how to group chunks into duration-based segments

    Estimate: 4500 tokens ≈ 3 minutes audio (adjust based on testing)

    Args:
        total_chunks: Total number of audio chunks
        target_duration_hours: Target duration for each segment in hours (default: 1.0)

    Returns:
        List of chunk index lists, where each sublist represents one segment
    """
    # Rough estimate: 4500 tokens ≈ 180 seconds (3 minutes)
    # This is conservative; actual may vary
    ESTIMATED_SECONDS_PER_CHUNK = 180
    TARGET_SEGMENT_SECONDS = target_duration_hours * 3600

    chunks_per_segment = int(TARGET_SEGMENT_SECONDS / ESTIMATED_SECONDS_PER_CHUNK)
    if chunks_per_segment < 1:
        chunks_per_segment = 1

    logger.info(f"Estimated {ESTIMATED_SECONDS_PER_CHUNK}s per chunk, targeting {TARGET_SEGMENT_SECONDS}s segments")
    logger.info(f"Will group ~{chunks_per_segment} chunks per segment")

    segments = []
    for i in range(0, total_chunks, chunks_per_segment):
        segment_chunks = list(range(i, min(i + chunks_per_segment, total_chunks)))
        segments.append(segment_chunks)

    logger.info(f"Created {len(segments)} segments from {total_chunks} chunks")
    for idx, segment in enumerate(segments):
        estimated_minutes = (len(segment) * ESTIMATED_SECONDS_PER_CHUNK) / 60
        logger.info(f"  Segment {idx+1}: {len(segment)} chunks (~{estimated_minutes:.1f} minutes)")

    return segments


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for audio stitching
    Changed to whole-book processing with duration-based segments

    Triggered by completion messages from TTS generation lambda

    New SQS Message format (whole-book):
    {
        "job_id": "book-123",
        "chunk_index": 0,
        "total_chunks": 50,
        "audio_s3_key": "book-123/chunk_0000.wav",
        "audio_bucket": "audio-chunks-bucket",
        "status": "audio_generated"
    }
    """
    try:
        # Check if this is an SQS event or direct invocation
        records = event.get('Records', [])

        if records:
            # Process SQS records (chunk completion notifications)
            results = []

            for record in records:
                try:
                    message_body = json.loads(record['body'])

                    job_id = message_body['job_id']
                    chunk_index = message_body['chunk_index']
                    total_chunks = message_body['total_chunks']

                    logger.info(
                        f"Chunk completion notification: Job {job_id}, "
                        f"Chunk {chunk_index+1}/{total_chunks}"
                    )

                    # Update tracking and check if all chunks are complete
                    all_complete = update_chunk_status(job_id, chunk_index, total_chunks)

                    if all_complete:
                        logger.info(f"All {total_chunks} chunks complete for job {job_id}, starting duration-based stitching")

                        # Calculate duration-based segments (~1 hour each)
                        segments = calculate_duration_based_segments(total_chunks, target_duration_hours=1.0)

                        import os
                        segment_results = []

                        # Stitch each segment separately
                        for part_num, chunk_indices in enumerate(segments, start=1):
                            logger.info(f"Stitching part {part_num}/{len(segments)} with {len(chunk_indices)} chunks")

                            # Use streaming approach to reduce memory usage
                            temp_audio_path = create_stitched_audio_streaming(job_id, chunk_indices)

                            try:
                                # Read final audio from temp file for upload
                                with open(temp_audio_path, 'rb') as f:
                                    final_wav = f.read()

                                # Store final audio as part_XX.wav
                                final_key = store_final_audio(
                                    job_id, part_num, final_wav, total_parts=len(segments)
                                )

                                segment_results.append({
                                    'part_number': part_num,
                                    'output_key': final_key,
                                    'audio_size': len(final_wav),
                                    'chunk_count': len(chunk_indices),
                                    's3_location': f's3://{FINAL_AUDIO_BUCKET}/{final_key}'
                                })

                                logger.info(f"Successfully stitched part {part_num}: {final_key}")

                            finally:
                                # Clean up temp file
                                if os.path.exists(temp_audio_path):
                                    os.remove(temp_audio_path)

                        # Clean up ALL intermediate chunk files after all segments are done
                        all_chunk_indices = list(range(total_chunks))
                        cleanup_chunks(job_id, all_chunk_indices)

                        results.append({
                            'job_id': job_id,
                            'total_chunks': total_chunks,
                            'total_parts': len(segments),
                            'status': 'stitched',
                            'parts': segment_results
                        })

                        logger.info(f"Successfully stitched job {job_id} into {len(segments)} parts")

                    else:
                        results.append({
                            'job_id': job_id,
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
            # Direct invocation to force stitching (whole-book)
            job_id = event.get('job_id')
            total_chunks = event.get('total_chunks')
            target_duration_hours = event.get('target_duration_hours', 1.0)

            if not all([job_id, total_chunks]):
                return {
                    'statusCode': 400,
                    'body': json.dumps({
                        'error': 'Direct invocation requires job_id and total_chunks'
                    })
                }

            logger.info(f"Direct stitching request for job {job_id} with {total_chunks} chunks")

            # Calculate duration-based segments
            segments = calculate_duration_based_segments(total_chunks, target_duration_hours)

            import os
            segment_results = []

            # Stitch each segment
            for part_num, chunk_indices in enumerate(segments, start=1):
                temp_audio_path = create_stitched_audio_streaming(job_id, chunk_indices)

                try:
                    with open(temp_audio_path, 'rb') as f:
                        final_wav = f.read()

                    final_key = store_final_audio(job_id, part_num, final_wav, total_parts=len(segments))

                    segment_results.append({
                        'part_number': part_num,
                        'output_key': final_key,
                        'audio_size': len(final_wav),
                        's3_location': f's3://{FINAL_AUDIO_BUCKET}/{final_key}'
                    })

                finally:
                    if os.path.exists(temp_audio_path):
                        os.remove(temp_audio_path)

            return {
                'statusCode': 200,
                'body': json.dumps({
                    'message': f'Stitched {total_chunks} chunks into {len(segments)} parts',
                    'total_parts': len(segments),
                    'parts': segment_results
                })
            }

    except Exception as e:
        logger.error(f"Fatal error in lambda handler: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }