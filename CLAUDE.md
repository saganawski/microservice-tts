# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a microservice-based text-to-speech (TTS) system built with AWS CDK, Java 21, and Maven. The system processes document uploads through a series of Lambda functions orchestrated by S3 events and SQS queues, transforming text files into audio output via Gemini API-based TTS providers.

## Architecture - Chunked TTS Processing (Updated)

The system uses a **chunked processing architecture** to handle large documents efficiently and avoid timeouts:

### Core Stacks

#### FileFlowStack
- **OriginalFileBucket**: Stores uploaded files (PDF/TXT/EPUB)
- **TextChunksBucket**: Stores text chunks (4.5k tokens each) for TTS processing
- **AudioChunksBucket**: Stores individual audio chunks before stitching
- **ProcessedFileBucket**: Stores final concatenated audio files with crossfading

### Lambda Functions (Chunked Architecture)

1. **OrchestratorLambda**
   - Analyzes PDF structure using Gemini Vision API
   - Detects chapters and page boundaries
   - Queues chapters for processing in SQS FIFO queue

2. **ChapterTextExtractorLambda** (Updated)
   - Extracts text from specific chapter pages using Gemini API
   - Chunks text into 4,500 token segments (reduced from 9,000)
   - Stores text chunks in S3
   - Sends each chunk to TTS queue for parallel processing

3. **TTSGenerationLambda** (Updated with Audio Validation)
   - Processes individual text chunks from SQS queue
   - **Rate Limited**: Implements token-based delay to stay under 10k tokens/minute
   - **Concurrency**: Limited to 2 concurrent executions (was 20)
   - Implements exponential backoff retry logic for API rate limits
   - **Audio Validation**: Validates duration, file size, format, and silence ratio
   - **Intelligent Retry**: Type-specific retry strategies for validation failures
   - **SNS Alerting**: Sends alerts when validation fails after all retries
   - Generates audio using Gemini TTS API
   - Stores audio chunks in S3
   - Sends completion notifications to stitching queue

4. **AudioStitchingLambda** (Updated with Streaming)
   - Monitors chunk completion via DynamoDB tracking
   - **Streaming Architecture**: Processes chunks sequentially to minimize memory usage
   - Downloads chunks one-by-one and writes to temporary file
   - Concatenates with 50ms crossfading while maintaining small overlap buffer
   - Memory usage reduced by 56% (3GB → 1.3GB)
   - Stores final chapter audio in ProcessedFileBucket
   - Cleans up intermediate chunk files and temp files

### Supporting Infrastructure

#### SQS Queues
- **ChapterQueue (FIFO)**: Chapter processing messages from orchestrator
- **TTSQueue (FIFO)**: Text chunks for TTS generation with DLQ for failures
- **StitchQueue (FIFO)**: Audio chunk completion notifications

#### DynamoDB
- **AudioChunkTracking**: Tracks chunk processing completion status

#### SNS Topics
- **ValidationAlertTopic**: Sends email alerts when audio validation fails after all retries

#### ApiStack
- **REST API**: Provides `/file-upload` POST endpoint via API Gateway
- **CloudWatch Integration**: Comprehensive logging with custom access log format
- **IAM Roles**: Proper permissions for API Gateway CloudWatch logging

## Processing Flow (Chunked Architecture)

1. **File Upload** → API Gateway receives file via `/file-upload` endpoint
2. **Validation** → ValidationLambda validates file type and stores in OriginalFileBucket
3. **Orchestration** → S3 event triggers OrchestratorLambda to analyze PDF structure
4. **Chapter Detection** → Identifies chapters via Gemini Vision API and queues in SQS
5. **Text Extraction** → ChapterTextExtractorLambda extracts text and chunks to 4.5k tokens
6. **Parallel TTS** → Multiple TTSGenerationLambda instances process chunks with retry logic
7. **Audio Stitching** → AudioStitchingLambda concatenates chunks with crossfading
8. **Storage** → Final audio files stored in ProcessedFileBucket

## Key Features of Chunked Architecture

### Reliability
- **No Lambda Timeouts**: Each chunk processes in <5 minutes
- **Automatic Retries**: Exponential backoff for API rate limits
- **Dead Letter Queues**: Failed messages preserved for debugging
- **Parallel Processing**: Multiple chunks processed simultaneously

### Scalability
- **Configurable Chunk Size**: Default 4,500 tokens (adjustable)
- **Reserved Concurrency**: Controls parallel execution limits
- **Queue-Based Decoupling**: Components scale independently

### Quality
- **Audio Crossfading**: 50ms crossfade between chunks
- **Metadata Preservation**: Chapter structure maintained
- **Automatic Cleanup**: Temporary files removed after processing

## Development Commands

### Required: Set Environment Variables Before Deployment

**CRITICAL:** The CDK reads environment variables during deployment. Set these BEFORE running `cdk deploy`:

```bash
# Required: Gemini API Key
export GEMINI_API_KEY="AIzaSyCsCODpn4VCbeLWjaOOZ0tAxDR0noRTaZw"

# Verify it's set
echo $GEMINI_API_KEY
```

**Alternative:** Copy `.env.example` to `.env`, fill in values, and run:
```bash
source .env
```

### Build and Package
```bash
# Build all Python lambdas with dependencies (REQUIRED before CDK deploy)
./build-python-lambdas.sh

# Build Java lambdas
mvn clean package -DskipTests

# Build specific Java lambda
mvn -f lambdas/file-validation-lambda/pom.xml package
mvn -f lambdas/presigned-url-lambda/pom.xml package

# Build specific Python lambda (if needed)
cd lambdas/audio-stitching-lambda && ./package.sh
cd lambdas/tts-generation-lambda && ./package.sh
```

### CDK Operations
```bash
# IMPORTANT: Set environment variables first!
export GEMINI_API_KEY="your-key-here"

# List all stacks
cdk ls

# Synthesize CloudFormation templates
cdk synth

# Deploy stacks (after building lambdas)
cdk deploy --all

# Deploy individual stack
cdk deploy FileFlowStack
cdk deploy ApiStack

# Show differences from deployed stacks
cdk diff

# Destroy stacks
cdk destroy --all
```

### Complete Deployment from Scratch
```bash
# 1. Set environment variables
export GEMINI_API_KEY="your-key-here"

# 2. Build all lambdas
./build-python-lambdas.sh
mvn clean package -DskipTests

# 3. Deploy all stacks
cdk deploy --all

# 4. Verify deployment (see DEPLOYMENT_CHECKLIST.md)
```

### Testing
```bash
# Run all tests
mvn test

# Test specific module
mvn -f lambdas/tts-generation-lambda/pom.xml test

# Local lambda testing
python lambdas/tts-generation-lambda/handler.py
```

## Environment Variables

### Required for Deployment
- `GEMINI_API_KEY`: Google Gemini API key for text extraction and TTS

### Optional Configuration
- `CDK_DEFAULT_ACCOUNT`: AWS account number (default: 272765753210)
- `CDK_DEFAULT_REGION`: AWS region (default: us-east-1)

### Lambda-Specific Variables
#### ChapterTextExtractorLambda
- `GEMINI_TEXT_MODEL`: Text extraction model (default: gemini-2.5-pro)
- `TTS_QUEUE_URL`: SQS queue for TTS processing
- `CHUNKS_BUCKET`: S3 bucket for text chunks
- `MAX_TOKENS_PER_CHUNK`: Token limit per chunk (default: 4500)

#### TTSGenerationLambda
- `GEMINI_TTS_MODEL`: TTS model (default: gemini-2.5-pro-preview-tts)
- `GEMINI_TTS_VOICE`: Voice selection (default: Charon)
- `AUDIO_CHUNKS_BUCKET`: S3 bucket for audio chunks
- `STITCH_QUEUE_URL`: SQS queue for stitching notifications
- `MAX_RETRY_ATTEMPTS`: Retry attempts for API rate limits (default: 5)
- `INITIAL_WAIT`: Initial retry wait in seconds (default: 10)
- `MAX_WAIT`: Maximum retry wait in seconds (default: 120)
- `VALIDATION_ALERT_TOPIC_ARN`: SNS topic ARN for validation failure alerts

#### AudioStitchingLambda
- `AUDIO_CHUNKS_BUCKET`: S3 bucket for reading audio chunks
- `PROCESSED_BUCKET_NAME`: S3 bucket for final audio files
- `TRACKING_TABLE`: DynamoDB table for chunk tracking
- `CROSSFADE_DURATION_MS`: Crossfade duration in milliseconds (default: 50)

## Project Structure

```
microservice-tts/
├── cdk/                                # CDK infrastructure code
│   └── src/main/java/com/myorg/
│       ├── TtsApp.java                 # Main CDK application
│       ├── FileFlowStack.java          # S3 buckets and Lambda definitions
│       └── ApiStack.java               # API Gateway configuration
├── lambdas/
│   ├── orchestrator-lambda/            # PDF analysis and chapter detection
│   ├── chapter-text-extractor-lambda/  # Text extraction and chunking
│   │   ├── handler.py                  # Current implementation
│   │   └── handler_updated.py          # New chunked implementation
│   ├── tts-generation-lambda/          # TTS generation with retry logic
│   │   ├── handler.py
│   │   └── requirements.txt
│   └── audio-stitching-lambda/         # Audio concatenation with crossfading
│       ├── handler.py
│       └── requirements.txt
└── pom.xml                             # Multi-module Maven configuration
```

## Implementation Details

### Chunking Strategy
- **Token Limit**: 4,500 tokens per chunk (configurable)
- **Text Encoding**: Uses tiktoken (cl100k_base) for accurate token counting
- **Fallback**: Character-based chunking if tiktoken unavailable

### Rate Limiting Strategy (TTSGenerationLambda)

**Gemini TTS API Limit**: 10,000 tokens per minute

**Implementation**:
1. **Concurrency Limit**: Reserved concurrency set to 2 (reduced from 20)
   - Max theoretical usage: 2 × 4,500 tokens = 9,000 tokens/minute
   - Stays safely under 10k limit
2. **Token-Based Delay**: Each lambda calculates delay before API call
   - Formula: `delay = (estimated_tokens / 10000) × 60 seconds`
   - Example: 4,500 tokens → 27 second delay
   - Spreads requests over time to prevent bursts
3. **Exponential Backoff**: Still active for handling temporary rate limit errors

**Token Estimation**:
```python
word_count = len(chunk_text.split())
estimated_tokens = int(word_count * 1.3)  # ~1 token per 0.75 words
delay_seconds = (estimated_tokens / 10000) * 60
```

### Retry Logic (TTSGenerationLambda)
```python
@retry(
    stop=stop_after_attempt(5),
    wait=wait_exponential(multiplier=2, min=10, max=120),
    retry=retry_if_exception_type(ClientError)
)
def generate_speech_with_retry(...):
    # TTS generation with automatic retry
```

### Audio Validation (TTSGenerationLambda)

The TTS lambda includes comprehensive audio validation to catch problematic outputs from the Gemini TTS API:

**Validation Checks**:
1. **Format Validation**: Verifies WAV format (24kHz, mono, 16-bit PCM)
2. **Duration Validation**: Compares actual duration vs expected (based on word count at ~250 wpm)
3. **Size Validation**: Checks file size against calibrated expected size (~31MB per chunk)
4. **Silence Detection**: Uses RMS energy analysis to detect silent audio (>25% silence = retry)

**Calibrated Metrics** (from production data):
| Metric | Value |
|--------|-------|
| Expected file size | 31,446,330 bytes (~30.0 MB) |
| Expected duration | ~655 seconds (~10.9 minutes) |
| Speech rate | ~250 words/minute |
| Size tolerance | ±10% |
| Duration tolerance | ±20% (±50% for last chunk) |

**Intelligent Retry Strategy**:
| Failure Type | Max Retries | Delay Multiplier |
|--------------|-------------|------------------|
| Format error | 2 | 1.0x |
| Duration error | 3 | 1.5x |
| Size error | 3 | 1.5x |
| Silence error | 2 | 2.0x |

**Observability**:
```json
{
  "event": "AUDIO_VALIDATION_METRICS",
  "passed": true,
  "attempt": 1,
  "duration_ratio": 0.98,
  "silence_ratio": 0.05,
  "size_ratio": 1.02,
  "avg_rms_energy": 1523.4
}
```

**Alerting**: Failed validations after all retries trigger SNS notifications to the configured email.

### Audio Processing
- **Format**: WAV, 24kHz, 16-bit, mono
- **Crossfading**: Linear interpolation over 50ms
- **Parallel Downloads**: ThreadPoolExecutor for chunk retrieval
- **Memory Management**: 2GB for stitching lambda

### Error Handling
- **Rate Limits**: Exponential backoff with jitter
- **Failed Chunks**: Dead letter queue for investigation
- **Partial Failures**: DynamoDB tracking ensures consistency
- **Cleanup**: Automatic removal of temporary files

## Performance Characteristics

### Processing Times (Typical)
- **Text Extraction**: 10-30 seconds per chapter
- **Chunking**: <1 second per chapter
- **TTS Generation**: 20-60 seconds per chunk
- **Audio Stitching**: 10-30 seconds per chapter
- **Total**: 2-5 minutes per chapter (parallel processing)

### Resource Usage
- **Lambda Memory**: 512MB-2GB depending on function
- **S3 Storage**: ~10MB per chapter (final audio)
- **API Calls**: 1 text extraction + N TTS calls per chapter

### Cost Optimization
- **Reserved Concurrency**: Prevents runaway costs
- **Lifecycle Rules**: Auto-cleanup of temporary files
- **DynamoDB On-Demand**: Pay-per-use pricing
- **SQS FIFO**: Prevents duplicate processing

## Troubleshooting

### Common Issues

#### Lambda Timeouts
- **Symptom**: Function times out after 15 minutes
- **Solution**: Implemented chunking to process smaller segments

#### API Rate Limits (MITIGATED)
- **Symptom**: 429 RESOURCE_EXHAUSTED errors from Gemini TTS API
- **Root Cause**: 10,000 tokens/minute limit
- **Solution Implemented**:
  - Reserved concurrency reduced from 20 to 2
  - Token-based delay added before each API call
  - Exponential backoff retry logic for transient errors
- **Expected Behavior**: Should rarely hit rate limits now

#### Memory Issues (RESOLVED with Streaming)
- **Previous Issue**: Lambda ran out of memory (3GB) during parallel chunk processing
- **Solution Implemented**: Sequential streaming approach in AudioStitchingLambda
- **Results**: Memory usage reduced from 3GB to 1.3GB (56% reduction)
- **Details**: Processes chunks one-by-one, writes to /tmp, maintains small overlap buffer
- **Fallback**: If issues persist, check chunk sizes and /tmp usage (512MB limit)

#### Missing Chunks
- **Symptom**: Final audio missing sections
- **Solution**: DynamoDB tracking ensures all chunks processed

#### Audio Validation Failures
- **Symptom**: SNS alerts for validation failures, chunks in DLQ
- **Root Causes**:
  - **Undersized files**: Gemini API returned truncated audio (~18-25MB vs ~30MB)
  - **Silent audio**: Correct size/duration but no speech content
  - **Duration mismatch**: Audio length doesn't match expected from text length
- **Solution Implemented**:
  - Comprehensive validation (format, duration, size, silence)
  - Intelligent retry with type-specific strategies (2-3 retries per failure type)
  - SNS alerting for persistent failures
- **Monitoring**:
  ```bash
  # Check validation metrics in CloudWatch Logs Insights
  fields @timestamp, @message
  | filter @message like /AUDIO_VALIDATION_METRICS/
  | parse @message '"passed": *,' as validation_passed
  | stats count(*) by validation_passed
  ```

### Monitoring
```bash
# Check TTS queue depth
aws sqs get-queue-attributes --queue-url <TTS_QUEUE_URL> --attribute-names ApproximateNumberOfMessages

# Monitor lambda logs
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda --follow

# Check DynamoDB tracking
aws dynamodb scan --table-name AudioChunkTracking
```

## Migration from Monolithic to Chunked Architecture

### Key Changes
1. **Reduced chunk size**: 9,000 → 4,500 tokens
2. **Split TTS processing**: Single lambda → Three specialized lambdas
3. **Added retry logic**: Handles API rate limits gracefully
4. **Parallel processing**: Multiple chunks processed simultaneously
5. **Audio crossfading**: Smooth transitions between chunks

### Backwards Compatibility
- Existing S3 buckets maintained
- Final output format unchanged
- API endpoints remain the same

## Future Enhancements

- [x] Audio validation and intelligent retry system (implemented)
- [x] SNS alerting for validation failures (implemented)
- [ ] Dynamic chunk size based on content complexity
- [ ] Multi-language TTS support
- [ ] Real-time processing status via WebSocket
- [ ] Batch processing optimization
- [ ] Cost analysis dashboard
- [ ] Alternative TTS provider support (OpenAI, AWS Polly)
- [ ] Audio format options (MP3, OGG)
- [ ] Variable speed playback markers

## Notes

- **API Quotas**: Monitor Gemini API usage to avoid rate limits
- **Concurrency Limits**: Adjust reserved concurrency based on API quotas
- **Storage Costs**: Implement lifecycle policies for temporary files
- **Security**: API keys stored in environment variables, consider AWS Secrets Manager
- **Monitoring**: CloudWatch alarms recommended for production deployments