# TTS Generation Lambda

## Overview
This Lambda function handles Text-to-Speech generation for individual text chunks with robust retry logic to handle API rate limits.

## Features
- Processes text chunks from SQS queue
- Exponential backoff retry for API rate limits (429 errors)
- Generates audio using Google Gemini TTS API
- Stores audio chunks in S3 for later stitching
- Sends completion notifications to stitching queue

## Configuration

### Environment Variables
- `GEMINI_API_KEY` (required): Google Gemini API key
- `AUDIO_CHUNKS_BUCKET`: S3 bucket for storing audio chunks
- `TEXT_CHUNKS_BUCKET`: S3 bucket for reading text chunks
- `STITCH_QUEUE_URL`: SQS queue URL for stitching notifications
- `GEMINI_TTS_MODEL`: TTS model (default: gemini-2.5-pro-preview-tts)
- `GEMINI_TTS_VOICE`: Voice selection (default: Charon)
- `MAX_RETRY_ATTEMPTS`: Max retry attempts (default: 5)
- `INITIAL_WAIT`: Initial retry wait seconds (default: 10)
- `MAX_WAIT`: Maximum retry wait seconds (default: 120)

### Retry Logic
```python
@retry(
    stop=stop_after_attempt(5),
    wait=wait_exponential(multiplier=2, min=10, max=120)
)
```

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
        "text_s3_key": "job-123/chapter_01/chunk_000.txt",
        "text_bucket": "text-chunks-bucket"
    }
}
```

## Output
- Audio chunk stored in S3: `{job_id}/chapter_{num}/audio_chunk_{index}.wav`
- Notification sent to stitching queue

## Performance
- Timeout: 5 minutes
- Memory: 512MB
- Reserved Concurrency: 20 (control API usage)

## Error Handling
- **Rate Limits (429)**: Automatic retry with exponential backoff
- **Empty Responses**: Raises ValueError for debugging
- **Failed Messages**: Sent to Dead Letter Queue after 3 attempts

## Testing
```bash
# Local test
python handler.py

# Deploy and test
mvn package
cdk deploy FileFlowStack
```

## Monitoring
```bash
# Check queue depth
aws sqs get-queue-attributes --queue-url $TTS_QUEUE_URL \
    --attribute-names ApproximateNumberOfMessages

# Monitor logs
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda --follow

# Check DLQ
aws sqs receive-message --queue-url $TTS_DLQ_URL
```

## Common Issues

### Rate Limit Errors
- **Symptom**: 429 RESOURCE_EXHAUSTED
- **Solution**: Automatic retry handles this, adjust concurrency if persistent

### Memory Issues
- **Symptom**: Lambda runs out of memory
- **Solution**: Increase memory allocation in CDK stack

### Missing Audio Data
- **Symptom**: ValueError: No audio content in response
- **Solution**: Check API response, may need to adjust prompt