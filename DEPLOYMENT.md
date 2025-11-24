# Deployment Guide

This guide explains how to deploy the TTS microservice infrastructure using AWS CDK.

## Prerequisites

- AWS CLI configured with appropriate credentials
- Java 21
- Maven 3.x
- Python 3.12
- Node.js (for CDK CLI)
- AWS CDK CLI installed (`npm install -g aws-cdk`)

## Environment Variables

Before deployment, ensure you have the following environment variable set:

```bash
export GEMINI_API_KEY="your-gemini-api-key"
```

This will be used by the Gemini-based Lambda functions for text extraction and TTS generation.

## Build Process

The deployment requires building both Java and Python Lambda functions.

### 1. Build Python Lambdas

Run the provided build script to install dependencies for all Python Lambda functions:

```bash
./build-python-lambdas.sh
```

This script will:
- Install dependencies for `audio-stitching-lambda` (boto3)
- Install dependencies for `tts-generation-lambda` (boto3, google-genai, tiktoken, tenacity)
- Install dependencies for `chapter-text-extractor-lambda` (google-genai, tiktoken, boto3)
- Install dependencies for `orchestrator-lambda` (google-genai, boto3)

### 2. Build Java Lambdas

Build all Java Lambda functions using Maven:

```bash
mvn clean package -DskipTests
```

This compiles and packages all Java Lambda functions with their dependencies.

## Deployment

### Deploy All Stacks

To deploy all infrastructure stacks:

```bash
cdk deploy --all
```

### Deploy Individual Stacks

To deploy a specific stack:

```bash
# Deploy the file processing stack
cdk deploy FileFlowStack

# Deploy the API Gateway stack
cdk deploy ApiStack
```

### First-Time Deployment

If this is your first time deploying CDK in this AWS account/region, you'll need to bootstrap:

```bash
cdk bootstrap aws://ACCOUNT-NUMBER/REGION
```

## Verification

After deployment, verify the infrastructure:

### Check Lambda Functions

```bash
aws lambda list-functions --query 'Functions[?contains(FunctionName, `FileFlowStack`)].{Name:FunctionName, Runtime:Runtime, Memory:MemorySize}' --output table
```

### Check S3 Buckets

```bash
aws s3 ls | grep -E "(original|processed|audio-chunks|text-chunks)"
```

### Check SQS Queues

```bash
aws sqs list-queues | grep -E "(Chapter|TTS|Stitch)"
```

## Post-Deployment Testing

### Test Audio Stitching Lambda

You can test the refactored audio-stitching-lambda with existing chunks:

```bash
# Create test payload
cat > test_payload.json << 'EOF'
{
  "job_id": "test-job",
  "chapter_number": 1,
  "total_chunks": 3,
  "chapter_title": "Test Chapter"
}
EOF

# Invoke lambda
LAMBDA_NAME=$(aws lambda list-functions --query 'Functions[?contains(FunctionName, `AudioStitchingLambda`)].FunctionName' --output text)
aws lambda invoke --function-name $LAMBDA_NAME --cli-binary-format raw-in-base64-out --payload file://test_payload.json response.json

# Check response
cat response.json | jq .
```

## Monitoring

### CloudWatch Logs

Monitor Lambda execution logs:

```bash
# Audio Stitching Lambda
aws logs tail /aws/lambda/FileFlowStack-AudioStitchingLambda --follow

# TTS Generation Lambda
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda --follow
```

### DynamoDB Tracking

Check chunk processing status:

```bash
aws dynamodb scan --table-name AudioChunkTracking --output table
```

## Updating Lambda Code

### Update Python Lambda

After making code changes to a Python Lambda:

```bash
# Rebuild dependencies
./build-python-lambdas.sh

# Deploy just the FileFlowStack (contains all lambdas)
cdk deploy FileFlowStack
```

### Update Java Lambda

After making code changes to a Java Lambda:

```bash
# Rebuild
mvn clean package -DskipTests

# Deploy
cdk deploy FileFlowStack
```

## Rollback

If you need to rollback a deployment:

```bash
# Check CloudFormation stack history
aws cloudformation describe-stack-events --stack-name FileFlowStack | head -50

# Rollback is automatic if deployment fails
# For manual rollback, redeploy the previous code version
```

## Cleanup

To remove all infrastructure:

```bash
cdk destroy --all
```

**Warning:** This will delete all S3 buckets, Lambda functions, and DynamoDB tables. Make sure to backup any important data first.

## Troubleshooting

### Python Dependencies Not Found

If Lambda functions fail due to missing dependencies:

```bash
cd lambdas/audio-stitching-lambda
pip install -r requirements.txt -t . --upgrade
```

### CDK Synthesis Errors

If `cdk synth` fails:

```bash
# Clear CDK cache
rm -rf cdk.out/

# Rebuild Java lambdas
mvn clean package -DskipTests

# Try synthesis again
cdk synth
```

### Lambda Timeout Errors

The audio-stitching-lambda now uses streaming processing and should not timeout. If timeouts occur:

1. Check CloudWatch logs for the specific error
2. Verify chunk sizes are reasonable (<50MB each)
3. Ensure Lambda has sufficient memory (currently set to 3008MB)

### Memory Errors (RESOLVED)

The streaming implementation resolved previous out-of-memory errors. Current memory usage:
- **Before:** 3008MB (out of memory)
- **After:** ~1331MB (56% reduction)

If memory issues still occur, check for:
- Extremely large audio files (>100MB per chunk)
- `/tmp` directory size limits (512MB max)

## Architecture Notes

### Audio Stitching Lambda

The audio-stitching-lambda uses a **streaming approach** to minimize memory usage:

- Processes chunks sequentially (not all at once)
- Writes to temporary file in `/tmp` directory
- Maintains only small overlap buffer for crossfading
- Achieves 56% memory reduction vs parallel processing

### Key Components

- **FileFlowStack**: Contains all Lambda functions, S3 buckets, SQS queues
- **ApiStack**: Contains API Gateway and related resources
- **Buckets**: Original files, text chunks, audio chunks, processed files
- **Queues**: FIFO queues for chapter, TTS, and stitching operations
- **DynamoDB**: Tracks chunk completion status

## Performance Expectations

For a typical chapter:
- **Text Extraction**: 10-30 seconds
- **TTS Generation**: 20-60 seconds per chunk (parallel processing)
- **Audio Stitching**: 10-30 seconds per chapter
- **Total**: 2-5 minutes per chapter (depending on length)

Memory usage with streaming:
- **Audio Stitching Lambda**: ~1.3GB (down from 3GB)
- **TTS Generation Lambda**: ~512MB
- **Text Extraction Lambda**: ~1GB
