# Text-to-Speech Microservice

A serverless text-to-speech (TTS) system built on AWS using CDK (Java 21), Python 3.12 Lambdas, and Maven. Processes PDF/TXT/EPUB uploads through a chunked pipeline: Textract extracts text, splits into 4,500-token chunks, converts each to audio via Gemini TTS API, then stitches chunks into final WAV files with crossfading.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full pipeline diagram and resource inventory.

**Pipeline**: API Gateway -> FileValidation -> S3 -> Textract -> Chunking -> TTS (Gemini) -> Stitching -> Notification

**Key design decisions**:
- Chunked processing avoids Lambda timeout limits
- SQS FIFO queues provide ordered, deduplicated message delivery
- DynamoDB tracks distributed chunk completion
- Rate limiting (2 concurrent TTS lambdas) stays under Gemini's 10k tokens/min limit
- Audio validation catches truncated/silent output with intelligent retry

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
└── .env.example                            # Environment variable template
```

## Prerequisites

- **Java 21** (OpenJDK or Oracle JDK)
- **Apache Maven 3.6+**
- **Python 3.12** (for Lambda dependencies)
- **Node.js 18+** (for AWS CDK CLI)
- **AWS CDK CLI 2.x**: `npm install -g aws-cdk`
- **AWS CLI** configured with appropriate credentials
- **Gemini API Key** from [Google AI Studio](https://aistudio.google.com/app/apikey)

## Getting Started

```bash
# 1. Set up environment
cp .env.example .env
# Edit .env with your API keys
source .env

# 2. Build all lambdas
./build-python-lambdas.sh
mvn clean package -DskipTests

# 3. Bootstrap CDK (first time only)
cdk bootstrap

# 4. Deploy
cdk deploy --all
```

## Supported File Types

- PDF (`application/pdf`)
- EPUB (`application/epub+zip`)
- Plain Text (`text/plain`)

## Development

See [CLAUDE.md](CLAUDE.md) for detailed development commands, environment variables, and troubleshooting.
