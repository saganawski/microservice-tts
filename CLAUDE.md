# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A microservice-based text-to-speech (TTS) system built with AWS CDK (Java 21), Python 3.12 Lambdas, and Maven. Uploads PDF/TXT/EPUB files via API Gateway, extracts text with AWS Textract, chunks it into 4,500-token segments, converts each to audio via Gemini TTS API, then stitches chunks into final WAV files with crossfading.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full pipeline diagram and resource inventory.

## Project Structure

```
microservice-tts/
├── cdk/                                    # CDK infrastructure (Java)
│   └── src/main/java/com/myorg/
│       ├── TtsApp.java                     # CDK app entry point
│       ├── FileFlowStack.java              # S3, Lambda, SQS, SNS, DynamoDB
│       └── ApiStack.java                   # API Gateway
├── lambdas/
│   ├── file-validation-lambda/             # Java - validates uploads, stores in S3
│   ├── presigned-url-lambda/               # Java - upload URL generation
│   ├── textract-extraction-lambda/         # Python - PDF text extraction via Textract
│   ├── text-chunking-lambda/               # Python - splits text into 4500-token chunks
│   ├── tts-generation-lambda/              # Python - Gemini TTS with retry/validation
│   ├── audio-stitching-lambda/             # Python - concatenates chunks with crossfading
│   ├── notification-lambda/                # Python - email notifications with download links
│   └── audit-lambda/                       # Python - audio quality audit via Whisper
├── pom.xml                                 # Multi-module Maven config
├── build-python-lambdas.sh                 # Builds all Python lambda dependencies
├── .env.example                            # Environment variable template
├── ARCHITECTURE.md                         # Pipeline diagram and resource inventory
└── CODEBASE_ASSESSMENT.md                  # Project health assessment
```

## Processing Flow

1. **Upload** - Client POSTs file to API Gateway `/file-upload`
2. **Validate** - FileValidationLambda checks file type, stores in OriginalFileBucket
3. **Extract** - S3 event triggers TextractExtractionLambda (PDF → full text)
4. **Chunk** - TextChunkingLambda splits text into 4,500-token chunks via SQS
5. **TTS** - TTSGenerationLambda converts each chunk to WAV (2 concurrent, rate-limited)
6. **Stitch** - AudioStitchingLambda concatenates chunks with 50ms crossfade
7. **Notify** - NotificationLambda sends email with presigned download URLs

## Development Commands

### Environment Setup
```bash
# Copy and fill in .env, then source it
cp .env.example .env
# Edit .env with your API keys
source .env
```

### Build and Deploy
```bash
# Build all Python lambdas (REQUIRED before CDK deploy)
./build-python-lambdas.sh

# Build Java lambdas
mvn clean package -DskipTests

# Deploy all stacks
cdk deploy --all

# Other CDK commands
cdk ls          # List stacks
cdk synth       # Generate CloudFormation
cdk diff        # Preview changes
```

### Build Individual Lambdas
```bash
# Java
mvn -f lambdas/file-validation-lambda/pom.xml package
mvn -f lambdas/presigned-url-lambda/pom.xml package

# Python (if package.sh exists)
cd lambdas/audio-stitching-lambda && ./package.sh
cd lambdas/tts-generation-lambda && ./package.sh
```

## Environment Variables

### Required
- `GEMINI_API_KEY` - Google Gemini API key (TTS and text processing)

### Optional
- `OPENAI_API_KEY` - OpenAI API key (audit lambda, Whisper transcription)
- `CDK_DEFAULT_ACCOUNT` - AWS account (default: 272765753210)
- `CDK_DEFAULT_REGION` - AWS region (default: us-east-1)

### Lambda Environment (set by CDK)

**TextractExtractionLambda**: `TEXTRACT_RESULTS_BUCKET`, `CHUNKING_QUEUE_URL`

**TextChunkingLambda**: `TEXTRACT_RESULTS_BUCKET`, `TEXT_CHUNKS_BUCKET`, `TTS_QUEUE_URL`, `MAX_TOKENS_PER_CHUNK` (4500), `TTS_PROVIDER` (gemini|orpheus, default: gemini), `CHUNK_SIZE_TOKENS` (optional override; auto-sets to 1500 for orpheus)

**TTSGenerationLambda**: `GEMINI_API_KEY`, `AUDIO_CHUNKS_BUCKET`, `TEXT_CHUNKS_BUCKET`, `STITCH_QUEUE_URL`, `GEMINI_TTS_MODEL` (gemini-2.5-pro-preview-tts), `GEMINI_TTS_VOICE` (Charon), `MAX_RETRY_ATTEMPTS` (5), `INITIAL_WAIT` (10), `MAX_WAIT` (120), `VALIDATION_ALERT_TOPIC_ARN`, `MOSS_VOICE_REFERENCE` (seed_045 — voice name for MOSS voice cloning; WAV stored at s3://tts-eval-data-272765753210/voices/)

**AudioStitchingLambda**: `AUDIO_CHUNKS_BUCKET`, `PROCESSED_BUCKET_NAME`, `TRACKING_TABLE`, `CROSSFADE_DURATION_MS` (50), `JOB_COMPLETION_TOPIC_ARN`

**NotificationLambda**: `PROCESSED_BUCKET_NAME`, `NOTIFICATION_TOPIC_ARN`, `PRESIGNED_URL_EXPIRY_HOURS` (72)

**AuditLambda**: `OPENAI_API_KEY`, `TEXT_CHUNKS_BUCKET`, `AUDIO_CHUNKS_BUCKET`, `AUDIT_RESULTS_BUCKET`, `AUDIT_SNS_TOPIC_ARN`, `WHISPER_MODEL` (whisper-1)

## Key Implementation Details

### Rate Limiting (TTSGenerationLambda)
Gemini TTS API limit: 10,000 tokens/minute. Mitigated by:
- Reserved concurrency = 2 (max 9,000 tokens/minute)
- Token-based delay: `(estimated_tokens / 10000) * 60` seconds before each call
- Exponential backoff retry: 5 attempts, 10-120s range

### Audio Validation (TTSGenerationLambda)
Validates WAV format (24kHz, mono, 16-bit), duration vs expected, file size (~31MB/chunk), and silence ratio (<25%). Failed validations trigger type-specific retries then SNS alerts.

### Streaming Stitching (AudioStitchingLambda)
Processes chunks sequentially to /tmp (4GB ephemeral storage), applying 50ms linear crossfade. Memory usage ~1.3GB (56% reduction from parallel approach). Splits output into ~1-hour parts.

### Chunking Strategy
Uses tiktoken (cl100k_base) for token counting with character-based fallback. Chunks at sentence boundaries within 4,500-token limit.

## Troubleshooting

### Monitoring
```bash
# Check queue depth
aws sqs get-queue-attributes --queue-url <TTS_QUEUE_URL> --attribute-names ApproximateNumberOfMessages

# Lambda logs
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda --follow

# DynamoDB tracking
aws dynamodb scan --table-name JobAudioChunkTracking
```

### Common Issues
- **Rate limits (429)**: Should be rare with concurrency=2 + token delay. Check DLQ for stuck messages.
- **Memory errors**: AudioStitchingLambda has 3GB + 4GB /tmp. If exceeded, reduce chunk count.
- **Missing chunks**: Check DynamoDB tracking table and DLQs.
- **Validation failures**: Check CloudWatch for `AUDIO_VALIDATION_METRICS` events.

## Notes

- API keys are in environment variables; consider migrating to AWS Secrets Manager
- No automated tests yet; see CODEBASE_ASSESSMENT.md for improvement priorities
- Audit system uses OpenAI Whisper ($0.006/min) and is triggered manually via SQS
