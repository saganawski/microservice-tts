# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a microservice-based text-to-speech (TTS) system built with AWS CDK, Java 21, and Maven. The system processes document uploads through a series of Lambda functions orchestrated by S3 events, transforming text files into audio output via API-based TTS providers.

## Architecture

The system consists of two main CDK stacks:

### FileFlowStack
- **OriginalFileBucket**: Stores uploaded files (PDF/TXT/EPUB)
- **MarkdownFileBucket**: Stores consolidated markdown files from OCR processing
- **ChaptersBucket**: Stores individual chapter chunks ready for TTS processing
- **ProcessedFileBucket**: Stores final audio files
- **ValidationLambda**: Validates file uploads and stores in OriginalFileBucket
- **TransformLambda**: Downloads files, processes with Mistral OCR API, generates consolidated markdown
- **ChapterSplitterLambda**: Splits markdown into chapters and chunks for TTS processing
- **TTSLambda**: Converts text chunks to audio via API calls (OpenAI or self-hosted TTS)

### ApiStack
- **REST API**: Provides `/file-upload` POST endpoint via API Gateway
- **CloudWatch Integration**: Comprehensive logging with custom access log format
- **IAM Roles**: Proper permissions for API Gateway CloudWatch logging

## Processing Flow

1. **File Upload** → API Gateway receives file via `/file-upload` endpoint
2. **Validation** → ValidationLambda validates file type and stores in OriginalFileBucket
3. **OCR Processing** → S3 event triggers TransformLambda to process file with Mistral OCR API, generates markdown
4. **Chapter Splitting** → MarkdownFileBucket event triggers ChapterSplitterLambda to split by H1 headers or word count
5. **TTS Conversion** → ChaptersBucket events trigger TTSLambda to convert each chunk via TTS API
6. **Storage** → Final audio files stored in ProcessedFileBucket with WAV concatenation and crossfading

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

# Deploy stacks
cdk deploy --all

# Deploy individual stack
cdk deploy FileFlowStack
cdk deploy ApiStack

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

The system supports multiple TTS providers through API calls configured via environment variables:

### OpenAI TTS (Default)
```bash
export TTS_PROVIDER=OPENAI
export OPENAI_API_KEY=sk-...
export TTS_VOICE=alloy
export TTS_MODEL=tts-1
export TTS_SPEED=1.0
```

### Self-Hosted TTS API
```bash
export TTS_PROVIDER=SELF_HOSTED
export TTS_ENDPOINT_URL=https://your-tts-api.com/v1/audio/speech
export TTS_API_KEY=your-api-key  # Optional
export TTS_AUTH_HEADER=Authorization  # Optional, defaults to "Authorization"
export TTS_VOICE=alloy
export TTS_MODEL=tts-1
```

### Additional TTS Configuration
```bash
export TTS_LANGUAGE=en  # Optional language code
export TTS_INSTRUCTIONS="Speak clearly and slowly"  # Optional instructions
export TTS_RESPONSE_FORMAT=wav  # Optional, audio format
```

## Project Structure

- **Root pom.xml**: Multi-module Maven project with Java 21
- **cdk/**: CDK infrastructure code
  - `TtsApp.java`: Main CDK application entry point
  - `FileFlowStack.java`: S3 buckets and Lambda definitions with TTS provider configuration
  - `ApiStack.java`: API Gateway REST endpoint configuration
- **lambdas/**: Individual Lambda function modules
  - `file-validation-lambda`: File upload validation (PDF/TXT/EPUB)
  - `file-transform-lambda`: Mistral OCR API integration and markdown generation
  - `chapter-splitter-lambda`: Chapter detection and TTS-optimized chunking
  - `file-tts-lambda`: Multi-provider TTS conversion with WAV concatenation
  - `notification-lambda`: (Not currently integrated)

## Key Implementation Details

### General
- **Java 21** runtime across all Lambda components
- **AWS SDK v2** for S3 operations
- **Apache Commons FileUpload2** for multipart file handling
- **Account Number**: Hardcoded as `272765753210` in bucket names (configurable via CDK_DEFAULT_ACCOUNT)
- **Lambda Timeouts**: 5-15 minutes depending on function complexity
- **S3 Event Triggers**: Automatic processing pipeline via S3 notifications

### TTS Lambda Architecture
- **Provider Abstraction**: Interface-based design supports multiple TTS providers (FileFlowStack.java:90-132)
- **Provider Factory**: Dynamic provider instantiation based on `TTS_PROVIDER` environment variable
- **Supported Providers**:
  - `OpenAiTtsProvider`: OpenAI TTS API integration
  - `SelfHostedTtsProvider`: Generic HTTP API integration for self-hosted services
- **Audio Processing**: WAV concatenation with crossfading for seamless playback
- **PCM to WAV Conversion**: Handles format conversion for different TTS outputs

### Chapter Splitting Logic
- **Primary Method**: H1 header detection (`# Chapter Name`) for natural chapter boundaries
- **Fallback Method**: Word count-based splitting (~5000 words per chunk) when no headers found
- **Sanitization**: Chapter titles are cleaned for safe filenames
- **Metadata Generation**: JSON metadata tracks chapter structure and processing info

### OCR Processing
- **Mistral Pixtral API**: Used for PDF/EPUB OCR with vision capabilities
- **Consolidated Output**: All pages merged into single markdown file
- **Ordered Processing**: Segments ordered by index for correct page sequence

## Environment Variables Reference

### Required for Deployment
- `MISTRAL_API_KEY`: Mistral API key for OCR processing
- `OPENAI_API_KEY`: OpenAI API key (if using OpenAI TTS provider)

### Optional Configuration
- `TTS_PROVIDER`: TTS provider type (OPENAI or SELF_HOSTED, default: OPENAI)
- `TTS_ENDPOINT_URL`: Custom TTS API endpoint (required for SELF_HOSTED)
- `TTS_API_KEY`: API key for custom TTS endpoint (optional)
- `TTS_AUTH_HEADER`: HTTP header for authentication (optional, default: "Authorization")
- `TTS_VOICE`: Voice ID for TTS (provider-specific)
- `TTS_MODEL`: Model ID for TTS (provider-specific)
- `TTS_SPEED`: Speech speed multiplier (e.g., 1.0 for normal speed)
- `TTS_LANGUAGE`: Language code for TTS
- `TTS_INSTRUCTIONS`: Additional instructions for TTS provider
- `TTS_RESPONSE_FORMAT`: Audio format (e.g., wav, mp3)
- `CDK_DEFAULT_ACCOUNT`: AWS account number (default: 272765753210)
- `CDK_DEFAULT_REGION`: AWS region (default: us-east-1)

## Implementation Status

- ✅ **ValidationLambda**: Complete file upload validation for PDF/TXT/EPUB
- ✅ **TransformLambda**: Mistral OCR API integration with markdown generation
- ✅ **ChapterSplitterLambda**: H1 header detection with word-count fallback
- ✅ **TTSLambda**: Multi-provider TTS with OpenAI and self-hosted support
- ✅ **Audio Processing**: WAV concatenation with crossfading
- ❓ **NotificationLambda**: Exists but not integrated into pipeline

## Notes

- **API-Based Architecture**: All TTS processing happens via API calls from Lambda (no EC2 infrastructure required)
- **Cost Optimization**: Pay-per-use API pricing, no persistent compute resources
- **Bucket Versioning**: Disabled across all S3 buckets for cost savings
- **Chapter Detection**: Prioritizes natural chapter breaks (H1) over arbitrary word count splits
- **Audio Quality**: Crossfading prevents audible gaps between concatenated chunks
