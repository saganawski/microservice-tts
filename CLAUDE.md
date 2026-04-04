# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A microservice-based text-to-speech (TTS) system built with AWS CDK (Java 21), Python 3.12 Lambdas, and Maven. Uploads PDF/TXT/EPUB files via API Gateway, extracts text (Textract for PDF, ebooklib for EPUB), chunks it into provider-sized segments, converts each to audio via MOSS TTS (primary), and stitches chunks into final WAV files with crossfading. Supports three TTS providers — MOSS 8B (default), Orpheus 3B, and Gemini — with automatic Gemini fallback when EC2-hosted providers fail.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full pipeline diagram and resource inventory.

## Project Structure

```
microservice-tts/
├── cdk/                                    # CDK infrastructure (Java)
│   └── src/main/java/com/myorg/
│       ├── TtsApp.java                     # CDK app entry point
│       ├── FileFlowStack.java              # S3, Lambda, SQS, SNS, DynamoDB, Cognito, EC2 mgmt
│       └── ApiStack.java                   # API Gateway + Cognito authorizer + API key
├── lambdas/
│   ├── file-validation-lambda/             # Java - validates uploads, stores in S3, writes JobStatus
│   ├── presigned-url-lambda/               # Java - upload URL generation
│   ├── textract-extraction-lambda/         # Python - PDF (Textract) + EPUB (ebooklib) text extraction
│   ├── text-chunking-lambda/               # Python - provider-aware chunk sizing (670/170/4500 tokens)
│   ├── tts-generation-lambda/              # Python - multi-provider TTS (MOSS/Orpheus/Gemini)
│   ├── audio-stitching-lambda/             # Python - concatenates chunks with crossfading
│   ├── notification-lambda/                # Python - SES email + SNS fallback with download links
│   ├── ec2-manager-lambda/                 # Python - auto start/stop MOSS EC2 instance
│   ├── emotion-preprocessing/              # Python - Gemini emotion tagging (deployed, currently skipped)
│   ├── job-status-lambda/                  # Python - GET /status/{jobId} handler
│   └── audit-lambda/                       # Python - audio quality audit via Whisper
├── moss-server/                            # MOSS TTS FastAPI server (deployed on EC2, not in CDK)
├── orpheus-server/                         # Orpheus TTS FastAPI server (deployed on EC2, not in CDK)
├── pom.xml                                 # Multi-module Maven config
├── build-python-lambdas.sh                 # Builds all Python lambda dependencies
├── .env.example                            # Environment variable template
├── ARCHITECTURE.md                         # Pipeline diagram and resource inventory
└── CODEBASE_ASSESSMENT.md                  # Project health assessment
```

## Processing Flow

1. **Upload** — Client POSTs file to API Gateway `/file-upload` (Cognito JWT + API key required)
2. **Validate** — FileValidationLambda checks file type, stores in OriginalFileBucket, creates JobStatus record
3. **Extract** — S3 event triggers TextractExtractionLambda (PDF → Textract, EPUB → ebooklib with boilerplate filtering)
4. **Chunk** — TextChunkingLambda splits text into provider-sized chunks via SQS (MOSS: 670 tokens, Orpheus: 170, Gemini: 4500)
5. **EC2 Start** — CloudWatch Alarm detects TTS queue depth > 0, triggers EC2ManagerLambda to start GPU instance
6. **TTS** — TTSGenerationLambda converts each chunk to WAV via MOSS (primary) with Gemini fallback
7. **Stitch** — AudioStitchingLambda concatenates chunks with 50ms crossfade, splits into ~1-hour parts
8. **Notify** — NotificationLambda sends SES email with presigned download URLs (72h expiry)
9. **EC2 Stop** — EventBridge rule (every 5 min) triggers idle check; stops EC2 after 15 min of no queue activity

## TTS Providers

### MOSS 8B (Default — `TTS_PROVIDER=moss`)
- **Model:** OpenMOSS-Team/MOSS-TTS 8B with voice cloning
- **EC2 Instance:** `i-0ed07110e0cc39f9a` (g5.xlarge, 1x A10G 24GB VRAM)
- **AMI:** `ami-0ca1335cba0bab932` (moss-tts-dedicated-fast-load-20260330) — `device_map=auto`, 3s cold start
- **Server:** FastAPI on port 8000, systemd service `moss-tts.service`
- **Voice cloning:** Reference WAVs stored at `s3://tts-eval-data-272765753210/voices/`, default voice: `seed_045`
- **Chunk size:** 670 tokens (~500 words)
- **SSM param:** `/microservice-tts/moss-api-url` (updated dynamically on EC2 start)
- **Cost:** ~$1.01/hr on-demand; auto-stopped after 15 min idle

### Orpheus 3B (`TTS_PROVIDER=orpheus`)
- **Model:** `unsloth/orpheus-3b-0.1-ft` via vLLM AsyncLLMEngine
- **Same EC2 instance** as MOSS (only one can run at a time — 4 vCPU G-instance quota)
- **Voices:** tara, leah, jess, leo, dan, mia, zac, zoe, julia
- **Chunk size:** 170 tokens (~127 words) — hard limit from 8192 output token cap
- **SSM param:** `/microservice-tts/orpheus-api-url`

### Gemini (`TTS_PROVIDER=gemini`)
- **Model:** `gemini-2.5-pro-preview-tts`, voice: Charon
- **API key:** Secrets Manager (`microservice-tts/gemini-api-key`)
- **Chunk size:** 4500 tokens
- **Rate limit:** 10,000 tokens/min — mitigated by reserved concurrency + token-based delay
- **Also serves as fallback** when MOSS or Orpheus fail

## EC2 GPU Instance Management

- **Instance:** `i-0ed07110e0cc39f9a` (g5.xlarge, 100GB gp3, us-east-1)
- **vCPU quota:** 4 vCPUs for G-type instances (`L-DB2E81BA`) — only one G-instance can run at a time
- **Auto-start:** CloudWatch Alarm on TTS queue depth > 0 → SNS → EC2ManagerLambda starts instance, waits for health check, updates SSM
- **Auto-stop:** EventBridge rule every 5 min → EC2ManagerLambda checks queue; if empty for 15 min, stops instance
- **SSH:** `ssh -i ~/.ssh/orpheus-tts-key.pem ubuntu@<PUBLIC_IP>` (IP changes on stop/start)
- **Security group:** `sg-093af83a01abe49f8` (SSH:22, API:8000)

## Authentication & API

- **Cognito User Pool:** `tts-audiobook-users` (email sign-in, self-signup enabled)
- **API Key:** Rate-limited via Usage Plan (10 req/s, 1000/day)
- **Endpoints:**
  - `POST /file-upload` — Upload file (Cognito JWT + API key)
  - `POST /generate-upload-url` — Get presigned upload URL (Cognito JWT + API key)
  - `GET /status/{jobId}` — Poll job progress (Cognito JWT + API key)

## DynamoDB Tables

- **JobAudioChunkTracking** — Tracks chunk completion per job (partition key: `job_id`, TTL enabled)
- **JobStatus** — Frontend progress polling (partition key: `job_id`, TTL enabled)
  - Statuses: `UPLOADED` → `EXTRACTING` → `CHUNKING` → `GENERATING` → `STITCHING` → `COMPLETE` / `FAILED`
  - Fields: `progress` (0-100), `total_chunks`, `completed_chunks`, `file_name`, `user_email`, `output_url`, `error_message`

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
- `GEMINI_API_KEY` — Stored in Secrets Manager (`microservice-tts/gemini-api-key`), not needed as env var for deploy

### Optional
- `OPENAI_API_KEY` — OpenAI API key (audit lambda, Whisper transcription)
- `CDK_DEFAULT_ACCOUNT` — AWS account (default: 272765753210)
- `CDK_DEFAULT_REGION` — AWS region (default: us-east-1)

### Lambda Environment (set by CDK)

**TextractExtractionLambda**: `TEXTRACT_RESULTS_BUCKET`, `EMOTION_QUEUE_URL`, `CHUNKING_QUEUE_URL`, `SKIP_EMOTION` (true), `JOB_STATUS_TABLE`

**TextChunkingLambda**: `TEXTRACT_RESULTS_BUCKET`, `TEXT_CHUNKS_BUCKET`, `TTS_QUEUE_URL`, `TTS_PROVIDER` (moss), `JOB_STATUS_TABLE`

**TTSGenerationLambda**: `GEMINI_SECRET_NAME`, `AUDIO_CHUNKS_BUCKET`, `TEXT_CHUNKS_BUCKET`, `STITCH_QUEUE_URL`, `TTS_PROVIDER` (moss), `MOSS_SSM_PARAM`, `ORPHEUS_SSM_PARAM`, `ORPHEUS_VOICE` (tara), `GEMINI_TTS_MODEL` (gemini-2.5-pro-preview-tts), `GEMINI_TTS_VOICE` (Charon), `MAX_RETRY_ATTEMPTS` (5), `INITIAL_WAIT` (10), `MAX_WAIT` (120), `VALIDATION_ALERT_TOPIC_ARN`, `JOB_STATUS_TABLE`

**AudioStitchingLambda**: `AUDIO_CHUNKS_BUCKET`, `PROCESSED_BUCKET_NAME`, `TRACKING_TABLE`, `CROSSFADE_DURATION_MS` (50), `JOB_COMPLETION_TOPIC_ARN`, `JOB_STATUS_TABLE`

**NotificationLambda**: `PROCESSED_BUCKET_NAME`, `NOTIFICATION_TOPIC_ARN`, `PRESIGNED_URL_EXPIRY_HOURS` (72), `SES_FROM_EMAIL`, `JOB_STATUS_TABLE`

**EC2ManagerLambda**: `EC2_INSTANCE_ID`, `SSM_PARAM_NAME`, `SSM_LAST_ACTIVITY_PARAM`, `TTS_QUEUE_URL`, `IDLE_TIMEOUT_MINUTES` (15), `HEALTH_CHECK_TIMEOUT` (180)

**JobStatusLambda**: `JOB_STATUS_TABLE`

**EmotionPreprocessingLambda**: `GEMINI_SECRET_NAME`, `EMOTION_LEVEL` (subtle), `TEXTRACT_RESULTS_BUCKET`, `CHUNKING_QUEUE_URL`

**AuditLambda**: `OPENAI_API_KEY`, `TEXT_CHUNKS_BUCKET`, `AUDIO_CHUNKS_BUCKET`, `AUDIT_RESULTS_BUCKET`, `AUDIT_SNS_TOPIC_ARN`, `WHISPER_MODEL` (whisper-1)

## Key Implementation Details

### Multi-Provider TTS with Fallback
EC2-hosted providers (MOSS, Orpheus) automatically fall back to Gemini on connection errors, timeouts, or HTTP failures. MOSS uses voice cloning via S3-hosted reference WAVs; a deterministic seed per job ensures consistent voice across chunks.

### Rate Limiting (Gemini only)
Gemini TTS API limit: 10,000 tokens/minute. Mitigated by:
- Reserved concurrency = 1 (MOSS doesn't need rate limiting)
- Token-based delay: `(estimated_tokens / 10000) * 60` seconds before each Gemini call
- Exponential backoff retry: 5 attempts, 10-120s range

### EPUB Boilerplate Filtering
Layered detection for Project Gutenberg and other EPUB boilerplate:
1. Manifest/spine item IDs (pg-header, cover, toc)
2. epub:type values (titlepage, colophon, copyright-page)
3. CSS classes (pg-boilerplate)
4. Filename patterns (cover.*, titlepage.*)
5. Text patterns (Project Gutenberg markers)
6. Minimum content length threshold (50 chars)

### Streaming Stitching (AudioStitchingLambda)
Processes chunks sequentially to /tmp (4GB ephemeral storage), applying 50ms linear crossfade. Memory usage ~1.3GB (56% reduction from parallel approach). Splits output into ~1-hour parts.

### Chunking Strategy
Uses tiktoken (cl100k_base) for token counting with character-based fallback. Chunks at sentence boundaries. Provider-aware sizing: MOSS 670 tokens, Orpheus 170 tokens, Gemini 4500 tokens.

## Troubleshooting

### Monitoring
```bash
# Check queue depth
aws sqs get-queue-attributes --queue-url <TTS_QUEUE_URL> --attribute-names ApproximateNumberOfMessages

# Lambda logs
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda --follow

# DynamoDB tracking
aws dynamodb scan --table-name JobAudioChunkTracking

# Job status
aws dynamodb get-item --table-name JobStatus --key '{"job_id":{"S":"<JOB_ID>"}}'

# EC2 instance state
aws ec2 describe-instances --instance-ids i-0ed07110e0cc39f9a --query 'Reservations[0].Instances[0].State.Name'

# MOSS API URL from SSM
aws ssm get-parameter --name /microservice-tts/moss-api-url --query 'Parameter.Value'
```

### Common Issues
- **MOSS connection refused**: EC2 instance not running or systemd service not started. Check EC2 state and CloudWatch logs for EC2ManagerLambda.
- **Rate limits (429)**: Only applies to Gemini fallback. Check DLQ for stuck messages.
- **Memory errors**: AudioStitchingLambda has 3GB + 4GB /tmp. If exceeded, reduce chunk count.
- **Missing chunks**: Check DynamoDB tracking table and DLQs.
- **Validation failures**: Check CloudWatch for `AUDIO_VALIDATION_METRICS` events.
- **vCPU quota exceeded**: Only one g5.xlarge can run at a time (4 vCPU limit on G-type). Stop eval instances before starting MOSS.

## Notes

- Gemini API key is in Secrets Manager (`microservice-tts/gemini-api-key`), not environment variables
- API Gateway API key is in Secrets Manager (`microservice-tts/api-gateway-key`)
- No automated tests yet; see CODEBASE_ASSESSMENT.md for improvement priorities
- Audit system uses OpenAI Whisper ($0.006/min) and is triggered manually via SQS
- Emotion preprocessing lambda is deployed but skipped (`SKIP_EMOTION=true`) — MOSS doesn't use emotion tags
- MOSS AMI available: `ami-0ca1335cba0bab932` (moss-tts-dedicated-fast-load-20260330)
