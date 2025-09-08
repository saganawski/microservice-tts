# Text-to-Speech Microservice

A serverless text-to-speech (TTS) system built on AWS using AWS CDK, Java 21, and Maven. This microservice processes document uploads through a series of Lambda functions, transforming text files into audio output via TTS services.

## 🏗️ Architecture

The system consists of two main CDK stacks that create a fully managed serverless pipeline:

### FileFlowStack
- **OriginalFileBucket**: Stores uploaded files (PDF/TXT/EPUB)
- **ChunkFileBucket**: Stores text chunks split from original files for TTS processing
- **ProcessedFileBucket**: Stores final audio files
- **ValidationLambda**: Validates file uploads and stores in OriginalFileBucket
- **TransformLambda**: Downloads files, splits into 4096-character chunks for TTS API limits
- **TTSLambda**: Converts text chunks to audio files

### ApiStack
- **REST API**: Provides `/file-upload` POST endpoint via API Gateway
- **CloudWatch Integration**: Comprehensive logging with custom access log format
- **IAM Roles**: Proper permissions for API Gateway CloudWatch logging

## 🔄 Processing Flow

1. **File Upload** → API Gateway receives file via `/file-upload` endpoint
2. **Validation** → ValidationLambda validates file type and stores in OriginalFileBucket
3. **Transformation** → S3 event triggers TransformLambda to chunk file into 4096-char segments
4. **TTS Processing** → ChunkFileBucket events trigger TTSLambda to convert text to audio
5. **Storage** → Final audio files stored in ProcessedFileBucket

## 📁 Project Structure

```
microservice-tts/
├── cdk/                           # CDK infrastructure code
│   └── src/main/java/com/myorg/
│       ├── TtsApp.java           # Main CDK application
│       ├── FileFlowStack.java    # S3 buckets and Lambda definitions
│       └── ApiStack.java         # API Gateway configuration
├── lambdas/                      # Lambda function implementations
│   ├── file-validation-lambda/   # File upload validation and storage
│   ├── file-transform-lambda/    # Text extraction and chunking
│   ├── file-tts-lambda/         # Text-to-speech conversion
│   └── notification-lambda/      # (Not currently integrated)
├── pom.xml                      # Root Maven configuration
├── cdk.json                     # CDK configuration
└── CLAUDE.md                    # Development guidelines
```

## 🛠️ Prerequisites

### Local Development
- **Java 21** (OpenJDK or Oracle JDK)
- **Apache Maven 3.6+**
- **Node.js 18+** (for AWS CDK)
- **AWS CDK CLI 2.x**
```bash
npm install -g aws-cdk
```

### AWS Deployment
- **AWS CLI** configured with appropriate credentials
- **AWS Account** with sufficient permissions for:
  - S3 bucket creation and management
  - Lambda function deployment
  - API Gateway configuration
  - CloudWatch logging
  - IAM role creation

## 🚀 Getting Started

### 1. Clone and Build

```bash
git clone <repository-url>
cd microservice-tts
mvn package
```

### 2. Deploy to AWS

```bash
# Bootstrap CDK (first time only)
cdk bootstrap

# Deploy all stacks
cdk deploy --all

# Or deploy individual stacks
cdk deploy FileFlowStack
cdk deploy ApiStack
```

### 3. Verify Deployment

```bash
# List deployed stacks
cdk ls

# View stack outputs
aws cloudformation describe-stacks --stack-name FileFlowStack
aws cloudformation describe-stacks --stack-name ApiStack
```

## 🧪 Development Commands

### Build and Test
```bash
# Build entire project
mvn package

# Build specific lambda
mvn -f lambdas/file-validation-lambda/pom.xml package

# Run tests
mvn test

# Run tests for specific module
mvn -f cdk/pom.xml test
```

### CDK Operations
```bash
# List all stacks
cdk ls

# Synthesize CloudFormation templates
cdk synth

# Show differences from deployed stacks
cdk diff

# Destroy stacks (cleanup)
cdk destroy --all
```

## 📝 Supported File Types

- **PDF** (`application/pdf`)
- **EPUB** (`application/epub+zip`)  
- **Plain Text** (`text/plain`)

## 🔧 Configuration

### Environment Variables
- `ORIGINAL_BUCKET_NAME`: Set automatically by CDK deployment
- Account Number: Hardcoded as `272765753210` in bucket naming

### TTS Configuration
- **Chunk Limit**: 4096 characters per chunk (OpenAI TTS API requirement)
- **Lambda Timeout**: 5 minutes for all functions
- **Memory**: Configured per lambda function requirements

## 🚧 Current Implementation Status

- ✅ **ValidationLambda**: Complete file upload and validation
- ✅ **API Gateway**: REST endpoint with proper error handling
- 🚧 **TransformLambda**: File download implemented, chunking in progress
- 🚧 **TTSLambda**: Placeholder implementation
- ❓ **NotificationLambda**: Exists but not integrated

## 🔍 Monitoring and Logging

All Lambda functions include comprehensive CloudWatch logging:
- Request/response logging
- Error tracking and debugging
- Performance metrics
- S3 operation status

## 🤝 Contributing

1. Follow existing code conventions
2. Ensure all tests pass before submitting
3. Update documentation for significant changes
4. Use Java 21 features appropriately

## 📄 License

[Add your license information here]

## 🆘 Troubleshooting

### Common Issues

**Build Failures**
- Ensure Java 21 is installed and configured
- Run `mvn clean package` to clear build cache

**Deployment Issues** 
- Verify AWS credentials are configured
- Check CDK bootstrap status: `cdk bootstrap --show-template`
- Ensure sufficient AWS permissions

**Lambda Timeouts**
- Current timeout is 5 minutes
- Check CloudWatch logs for performance issues
- Consider increasing memory allocation for large files

---

*Built with ❤️ using AWS CDK and Java 21*