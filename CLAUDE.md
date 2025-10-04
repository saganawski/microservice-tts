# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a microservice-based text-to-speech (TTS) system built with AWS CDK, Java 21, and Maven. The system processes document uploads through a series of Lambda functions orchestrated by S3 events, transforming text files into audio output via configurable TTS providers (OpenAI or self-hosted VibeVoice).

## Architecture

The system consists of three main CDK stacks:

### FileFlowStack
- **OriginalFileBucket**: Stores uploaded files (PDF/TXT/EPUB)
- **MarkdownFileBucket**: Stores consolidated markdown files from OCR processing
- **ChaptersBucket**: Stores individual chapter chunks optimized for TTS processing
- **ProcessedFileBucket**: Stores final audio files
- **ValidationLambda**: Validates file uploads and stores in OriginalFileBucket
- **TransformLambda**: Downloads files, processes with Mistral OCR, generates consolidated markdown files
- **ChapterSplitterLambda**: Splits markdown files into chapters, chunks them based on TTS provider limits
- **TTSLambda**: Converts text chunks to audio using configured TTS provider (OpenAI or VibeVoice)

### ApiStack
- **REST API**: Provides `/file-upload` POST endpoint via API Gateway
- **CloudWatch Integration**: Comprehensive logging with custom access log format
- **IAM Roles**: Proper permissions for API Gateway CloudWatch logging

### VibeVoiceStack (Optional - GPU-based)
- **EC2 Instance**: g4dn.xlarge instance with NVIDIA T4 GPU for VibeVoice inference
- **VPC**: Custom VPC with public/private subnets and NAT gateway
- **Security Groups**: Port 8000 for HTTP traffic, port 22 for SSH
- **Elastic IP**: Stable endpoint for Lambda integration
- **Deep Learning AMI**: Pre-configured with NVIDIA drivers, CUDA 12.8, Docker, and NVIDIA Container Toolkit

## Processing Flow

1. **File Upload** → API Gateway receives file via `/file-upload` endpoint
2. **Validation** → ValidationLambda validates file type and stores in OriginalFileBucket
3. **Transformation** → S3 event triggers TransformLambda to process with Mistral OCR, generates markdown
4. **Chapter Splitting** → MarkdownFileBucket event triggers ChapterSplitterLambda to split by chapters and chunk for TTS
5. **TTS Processing** → ChaptersBucket events trigger TTSLambda to convert text chunks to audio
6. **Storage** → Final audio files stored in ProcessedFileBucket

## Development Commands

### Build and Package
```bash
# Build entire project
mvn package

# Build specific lambda
mvn -f lambdas/file-validation-lambda/pom.xml package
mvn -f lambdas/file-transform-lambda/pom.xml package
mvn -f lambdas/chapter-splitter-lambda/pom.xml package
mvn -f lambdas/file-tts-lambda/pom.xml package

# Clean build
mvn clean package
```

### CDK Operations
```bash
# List all stacks
cdk ls

# Synthesize CloudFormation templates
cdk synth

# Deploy core stacks (without GPU costs)
cdk deploy FileFlowStack ApiStack

# Deploy with VibeVoice GPU support (incurs EC2 costs)
export DEPLOY_VIBEVOICE=true
cdk deploy --all

# Show differences from deployed stacks
cdk diff

# Destroy stacks
cdk destroy --all
```

### Testing
```bash
# Run all tests
mvn test

# Run tests for specific module
mvn -f cdk/pom.xml test
mvn -f lambdas/file-tts-lambda/pom.xml test
```

## TTS Provider Configuration

The system supports multiple TTS providers configured via environment variables:

### OpenAI TTS (Default)
```bash
export TTS_PROVIDER=OPENAI
export OPENAI_API_KEY=sk-...
export TTS_VOICE=alloy
export TTS_MODEL=tts-1
```

### VibeVoice (Self-Hosted GPU)
```bash
export DEPLOY_VIBEVOICE=true  # Deploys VibeVoiceStack with EC2
# VIBEVOICE_ENDPOINT_URL is automatically configured from stack output
```

### Chunk Size Limits
- **OpenAI**: 4096 characters per chunk
- **VibeVoice**: 192000 characters per chunk (75% of 256k token limit)

The ChapterSplitterLambda automatically adjusts chunk sizes based on `TTS_PROVIDER`.

## VibeVoice Deployment

VibeVoice is deployed as a Flask-based REST API in a Docker container on EC2:

### Stack Deployment
```bash
export DEPLOY_VIBEVOICE=true
cdk deploy VibeVoiceStack
```

### Service Installation (After EC2 Launch)
The EC2 instance requires manual setup via SSM:

1. **Prepare service package** (create vibevoice-service.tar.gz from vibevoice-service/)
2. **Upload to S3** and generate presigned URL
3. **Deploy via SSM**:
```bash
INSTANCE_ID=$(aws cloudformation describe-stacks --stack-name VibeVoiceStack \
  --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceInstanceId`].OutputValue' --output text)

aws ssm send-command \
  --instance-ids $INSTANCE_ID \
  --document-name "AWS-RunShellScript" \
  --parameters 'commands=[
    "cd /opt/vibevoice",
    "curl -o vibevoice-service.tar.gz \"<PRESIGNED_URL>\"",
    "tar -xzf vibevoice-service.tar.gz",
    "docker build -t vibevoice-service .",
    "docker run -d --gpus all --restart unless-stopped -p 8000:8000 --name vibevoice vibevoice-service"
  ]'
```

### VibeVoice API Endpoints
- **POST /tts**: Convert text to speech (returns WAV audio)
- **POST /batch**: Batch TTS processing
- **GET /health**: Health check

## Project Structure

- **Root pom.xml**: Multi-module Maven project with Java 21
- **cdk/**: CDK infrastructure code
  - `TtsApp.java`: Main CDK application, conditionally creates VibeVoiceStack
  - `FileFlowStack.java`: S3 buckets and Lambda definitions
  - `ApiStack.java`: API Gateway configuration
  - `stacks/VibeVoiceStack.java`: EC2-based GPU inference stack
- **lambdas/**: Individual Lambda function modules
  - `file-validation-lambda`: File upload validation
  - `file-transform-lambda`: Mistral OCR processing and markdown generation
  - `chapter-splitter-lambda`: Chapter splitting and TTS-optimized chunking
  - `file-tts-lambda`: TTS conversion with provider abstraction
  - `notification-lambda`: (Not currently integrated)
- **vibevoice-service/**: Python Flask service for GPU-based TTS
  - `server.py`: Flask API with /tts and /health endpoints
  - `Dockerfile`: NVIDIA PyTorch base with CUDA 12.1
  - `requirements.txt`: Python dependencies (torch, flask, vibevoice)

## Key Implementation Details

- **Java 21** runtime across all Lambda components
- **AWS SDK v2** for S3 operations
- **Apache Commons FileUpload2** for multipart file handling
- **Account Number**: Default `272765753210`, can be overridden via `CDK_DEFAULT_ACCOUNT`
- **Lambda Timeouts**: 5-15 minutes depending on function
- **S3 Event Triggers**: Automatic processing pipeline
- **Provider Abstraction**: `file-tts-lambda` includes provider interface for OpenAI and VibeVoice

## Important Notes

- **VibeVoice requires GPU**: g4dn.xlarge instance costs ~$0.526/hour when deployed
- **Conditional Deployment**: VibeVoiceStack only deploys when `DEPLOY_VIBEVOICE=true`
- **Manual Service Installation**: After EC2 launch, VibeVoice Docker container must be deployed via SSM
- **Chapter-based Processing**: Files are split into chapters before chunking for better audio continuity
- **Audio Concatenation**: TTSLambda concatenates chunks with crossfading for seamless playback
