# Audio Stitching Lambda

## Overview
This Lambda function concatenates audio chunks into complete chapter audio files with crossfading for smooth playback using a memory-efficient streaming approach.

## Features
- Monitors chunk completion via DynamoDB tracking
- **NEW**: Sequential streaming processing (reduced memory usage by 56%)
- Applies 50ms crossfading between chunks
- Stores final audio in ProcessedFileBucket
- Cleans up intermediate chunk files

## Configuration

### Environment Variables
- `AUDIO_CHUNKS_BUCKET`: S3 bucket for reading audio chunks
- `PROCESSED_BUCKET_NAME`: S3 bucket for final audio files
- `TRACKING_TABLE`: DynamoDB table name for chunk tracking
- `CROSSFADE_DURATION_MS`: Crossfade duration (default: 50ms)

### DynamoDB Schema
Table: AudioChunkTracking
- Partition Key: `chapter_key` (format: `{job_id}#chapter_{num}`)
- Attributes:
  - `chunks_completed`: List of completed chunk indices
  - `total_chunks`: Total expected chunks
  - `ttl`: Time-to-live for automatic cleanup

## Input Message Format (from SQS)
```json
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
```

## Direct Invocation (Manual Stitching)
```json
{
    "job_id": "book-123",
    "chapter_number": 1,
    "total_chunks": 5,
    "chapter_title": "Introduction"
}
```

## Audio Processing

### Crossfading Algorithm
```python
def apply_crossfade(frames1, frames2, crossfade_samples):
    # Linear crossfade over specified samples
    for i in range(crossfade_samples):
        weight1 = 1.0 - (i / crossfade_samples)
        weight2 = i / crossfade_samples
        mixed = sample1 * weight1 + sample2 * weight2
```

### WAV Format
- Sample Rate: 24,000 Hz
- Channels: 1 (Mono)
- Bit Depth: 16-bit

## Performance
- Timeout: 10 minutes
- Memory: 3008MB allocated (but only ~1331MB used with streaming)
- Reserved Concurrency: 5
- **Streaming Processing**: Sequential chunk processing to minimize memory
- Temp Storage: Uses /tmp (512MB available) for intermediate file

## Workflow
1. Receive chunk completion notification
2. Update DynamoDB tracking
3. Check if all chunks complete
4. If complete (Streaming Mode):
   - Process chunks sequentially (one at a time)
   - Write to temporary file while maintaining crossfade overlap
   - Upload final audio from temp file
   - Clean up intermediate files and temp file

## Output
Final audio stored as: `jobs/{job_id}/chapter_{num:02d}.wav`

## Testing
```bash
# Local test
python handler.py

# Manual invocation
aws lambda invoke --function-name AudioStitchingLambda \
    --payload '{"job_id":"test","chapter_number":1,"total_chunks":3}' \
    response.json
```

## Monitoring
```bash
# Check tracking table
aws dynamodb scan --table-name AudioChunkTracking

# Monitor logs
aws logs tail /aws/lambda/FileFlowStack-AudioStitchingLambda --follow

# Check queue
aws sqs get-queue-attributes --queue-url $STITCH_QUEUE_URL \
    --attribute-names ApproximateNumberOfMessages
```

## Common Issues

### Missing Chunks
- **Symptom**: Stitching never triggers
- **Solution**: Check DynamoDB tracking for missing chunks

### Memory Errors (RESOLVED with Streaming)
- **Previous Issue**: Lambda ran out of memory with parallel processing
- **Solution Implemented**: Sequential streaming approach reduces memory from 3GB to ~1.3GB
- **Fallback**: If still encountering issues, check temp file size in /tmp (512MB limit)

### Crossfade Artifacts
- **Symptom**: Clicks or pops between chunks
- **Solution**: Adjust CROSSFADE_DURATION_MS

### Cleanup Failures
- **Symptom**: Temporary files not deleted
- **Solution**: S3 lifecycle rules provide fallback cleanup