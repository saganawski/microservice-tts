# Chapter Text Extractor and TTS Lambda

## Overview

This Lambda function is part of the new Gemini-based book processing pipeline. It consumes SQS messages from the orchestrator lambda, extracts chapter text using Gemini's vision API, chunks the text to fit TTS token limits, and generates concatenated audio files using Gemini TTS.

## Architecture

### Processing Flow

1. **Orchestrator Lambda** → SQS Queue → **This Lambda** → S3 (Processed Bucket)

2. **Detailed Steps:**
   - Consumes SQS messages containing chapter metadata and Google File API URIs
   - Uses Gemini vision model to extract text from specific page ranges
   - Chunks text to 16k tokens (half of Gemini TTS's 32k limit for safety)
   - Generates TTS audio for each chunk using Gemini TTS API
   - Concatenates all audio chunks into a single WAV file
   - Uploads final audio to S3 processed bucket

### SQS Message Format

```json
{
  "job_id": "book-123",
  "google_file_uri": "gs://generativelanguage.googleapis.com/...",
  "google_file_name": "projects/123/files/abc",
  "chapter": {
    "chapter_number": 1,
    "title": "Introduction",
    "start_page": 1,
    "end_page": 15
  },
  "text_bucket": "text-bucket-name"
}
```

## Environment Variables

### Required

- `GEMINI_API_KEY` - Google Gemini API key for text extraction and TTS
- `CHAPTER_QUEUE_URL` - SQS queue URL (not directly used by lambda, but configured in CDK)
- `PROCESSED_BUCKET_NAME` - S3 bucket for final audio files (default: `processed-file-bucket272765753210`)

### Optional

- `TEXT_BUCKET_NAME` - S3 bucket for storing extracted text (optional, not currently used)
- `GEMINI_TEXT_MODEL` - Model for text extraction (default: `gemini-2.5-pro`)
- `GEMINI_TTS_MODEL` - Model for TTS (default: `gemini-2.5-flash-preview-tts`)
- `GEMINI_TTS_VOICE` - Voice preset (default: `Charon`)

Available voices: `Puck`, `Charon`, `Kore`, `Fenrir`, `Aoede`

## Token Limits and Chunking

- **Gemini TTS Limit**: 32,000 tokens
- **Configured Chunk Size**: 16,000 tokens (conservative limit for safety)
- **Tokenizer**: Uses `tiktoken` with `cl100k_base` encoding (GPT-4 tokenizer as approximation)

The chunking strategy:
- Extracts full chapter text from Gemini
- Counts tokens accurately using tiktoken
- Splits into 16k token chunks
- Generates TTS for each chunk independently
- Concatenates WAV files sequentially (no crossfading to maintain simplicity)

## Audio Format

- **Format**: WAV (PCM)
- **Sample Rate**: 24,000 Hz
- **Channels**: Mono (1)
- **Bit Depth**: 16-bit (2 bytes per sample)

## Output Structure

Audio files are saved to S3 with the following structure:

```
s3://processed-bucket/
  └── jobs/
      └── {job_id}/
          ├── chapter_01.wav
          ├── chapter_02.wav
          ├── chapter_03.wav
          └── ...
```

### S3 Object Metadata

Each audio file includes metadata:
- `job_id` - Job identifier
- `chapter_number` - Chapter number
- `chapter_title` - Chapter title
- `chunks_count` - Number of text chunks processed

## Dependencies

See `requirements.txt`:

- `google-genai>=1.41.0` - Google Gemini API client
- `tiktoken>=0.5.0` - OpenAI's tokenizer for accurate token counting
- `boto3>=1.28.0` - AWS SDK for S3 and SQS

## Error Handling

- **Per-Record Processing**: Each SQS record is processed independently
- **Partial Failures**: If one chapter fails, others continue processing
- **SQS Visibility**: Failed messages return to queue for retry based on SQS configuration
- **Logging**: Comprehensive CloudWatch logging for debugging

## Comparison to Old Flow

### Old Flow (Java-based)
1. PDF → S3 → Mistral OCR → Markdown → S3
2. Markdown → Chapter Splitter → Chunks → S3
3. Chunks → TTS Lambda (OpenAI/Self-hosted) → Audio → S3

### New Flow (Gemini-based)
1. PDF → Orchestrator → Google File API → Gemini Analysis → SQS
2. SQS → **This Lambda** → Gemini Text Extraction + TTS → Audio → S3

**Advantages:**
- Single lambda handles both text extraction and TTS
- No intermediate markdown/text storage in S3
- Gemini vision model extracts text directly from PDF
- Native integration with Gemini TTS
- Automatic chunking based on token limits

## Cost Optimization

- **Lazy Client Init**: Gemini client initialized only when needed
- **Batch Processing**: Processes multiple SQS messages in single invocation
- **No Intermediate Storage**: Text extracted directly to TTS without S3 writes
- **Efficient Concatenation**: In-memory WAV concatenation without temp files

## Testing

### Local Testing

```bash
cd lambdas/chapter-text-extractor-lambda
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

export GEMINI_API_KEY="your-api-key"
export PROCESSED_BUCKET_NAME="your-bucket"

python handler.py
```

### Lambda Invocation

The lambda is triggered automatically by SQS, but can be tested directly:

```bash
aws lambda invoke \
  --function-name chapter-text-extractor-lambda \
  --payload file://test-event.json \
  response.json
```

### SQS Direct Testing

Send test message to SQS queue:

```bash
aws sqs send-message \
  --queue-url $CHAPTER_QUEUE_URL \
  --message-body file://test-message.json \
  --message-group-id "test-job"
```

## Future Enhancements

1. **Crossfading**: Add audio crossfading between chunks (like Java TTS lambda)
2. **Progress Tracking**: Publish progress events to EventBridge/SNS
3. **Text Caching**: Optionally cache extracted text in S3 for reprocessing
4. **Voice Customization**: Per-character voice selection from chapter metadata
5. **Parallel TTS**: Generate TTS for chunks in parallel using async processing
6. **Retry Logic**: Exponential backoff for transient Gemini API errors

## Monitoring

### CloudWatch Metrics

Monitor these metrics:
- Lambda duration (should stay under timeout)
- SQS message age
- Error rates
- Token usage (logged in CloudWatch)

### Key Log Messages

- `Extracting Chapter X: Title (pages Y-Z)` - Text extraction start
- `Created N chunks` - Chunking complete
- `Generating TTS for Chapter X, chunk Y/Z` - TTS progress
- `Concatenated WAV size: N bytes` - Final audio size
- `Successfully processed Chapter X` - Complete success

## Troubleshooting

### Common Issues

1. **"No SQS records found"**: Lambda invoked without SQS trigger
2. **"Failed to extract meaningful text"**: PDF page numbers may be incorrect or pages contain no text
3. **"Token limit exceeded"**: Chunk size may need adjustment for specific content
4. **S3 upload failures**: Check IAM permissions and bucket names

### Debug Steps

1. Check CloudWatch logs for detailed error messages
2. Verify SQS message format matches expected structure
3. Test Google File API URI accessibility
4. Validate Gemini API key and quota limits
5. Check S3 bucket permissions and regional settings
