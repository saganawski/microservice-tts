# Deployment Checklist

This document ensures all configuration is correct before deploying with CDK.

## Prerequisites

### 1. Set Environment Variables

The CDK reads the following environment variables during deployment:

```bash
# Required: Gemini API Key for TTS and text extraction
export GEMINI_API_KEY="AIzaSyCsCODpn4VCbeLWjaOOZ0tAxDR0noRTaZw"
```

**Verify it's set:**
```bash
echo $GEMINI_API_KEY
```

### 2. Build All Lambda Functions

```bash
# Build Python lambdas (REQUIRED before CDK deploy)
./build-python-lambdas.sh

# Build Java lambdas
mvn clean package -DskipTests
```

## Deployment Steps

### Full Stack Deployment

```bash
# 1. Ensure GEMINI_API_KEY is set (see above)
export GEMINI_API_KEY="your-key-here"

# 2. Build all lambdas
./build-python-lambdas.sh
mvn clean package -DskipTests

# 3. Deploy all stacks
cdk deploy --all
```

### Individual Stack Deployment

```bash
# Deploy only FileFlowStack (S3, SQS, Lambdas)
cdk deploy FileFlowStack

# Deploy only ApiStack (API Gateway)
cdk deploy ApiStack
```

## CDK Configuration Summary

The CDK automatically configures:

### TTS Generation Lambda Environment Variables
- ✅ `GEMINI_API_KEY` - From `$GEMINI_API_KEY` environment variable
- ✅ `STITCH_QUEUE_URL` - Auto-resolved to `stitch-notification-queue.fifo`
- ✅ `AUDIO_CHUNKS_BUCKET` - Auto-created
- ✅ `TEXT_CHUNKS_BUCKET` - Auto-created
- ✅ `GEMINI_TTS_MODEL` - `gemini-2.5-pro-preview-tts`
- ✅ `GEMINI_TTS_VOICE` - `Charon`

### Queue Names (FIFO)
- ✅ `chunking-queue.fifo` - For text chunking jobs
- ✅ `tts-processing-queue.fifo` - For TTS generation
- ✅ `stitch-notification-queue.fifo` - For audio stitching notifications

### Lambda Concurrency Limits
- ✅ TTS Generation: Reserved concurrency = 2 (rate limit compliance)
- ✅ Text Chunking: No limit (lightweight operation)
- ✅ Audio Stitching: No limit (triggered by completion)

## Verification After Deployment

### 1. Verify Lambda Environment Variables

```bash
# Check TTS Lambda config
aws lambda get-function-configuration \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text) \
  | jq '.Environment.Variables'
```

**Expected output should include:**
- `GEMINI_API_KEY`: Your API key (not empty!)
- `STITCH_QUEUE_URL`: Contains `stitch-notification-queue.fifo`
- All other variables populated

### 2. Verify Queue Creation

```bash
# List all queues
aws sqs list-queues | grep -E "(chunking|tts|stitch)"
```

**Expected queues:**
- `chunking-queue.fifo`
- `chunking-dlq.fifo`
- `tts-processing-queue.fifo`
- `tts-dlq.fifo`
- `stitch-notification-queue.fifo`

### 3. Verify S3 Buckets

```bash
# List buckets
aws s3 ls | grep -E "(original|textract|text-chunks|audio-chunks|processed)"
```

**Expected buckets:**
- `original-file-bucket272765753210`
- `textract-results-bucket272765753210`
- `text-chunks-bucket272765753210`
- `audio-chunks-bucket272765753210`
- `processed-file-bucket272765753210`

## Common Issues

### Issue: GEMINI_API_KEY is empty in Lambda

**Cause:** Environment variable not set before deployment

**Fix:**
```bash
# Set the variable
export GEMINI_API_KEY="your-key-here"

# Redeploy
cdk deploy FileFlowStack
```

### Issue: Wrong queue URL in lambda

**Symptom:** Error "The specified queue does not exist"

**Fix:** The CDK code is correct. This means the queue wasn't created properly or the lambda wasn't updated. Redeploy:
```bash
cdk deploy FileFlowStack
```

### Issue: Lambda code not updated

**Cause:** Forgot to run build scripts before deployment

**Fix:**
```bash
./build-python-lambdas.sh
mvn clean package -DskipTests
cdk deploy --all
```

## Clean Deployment from Scratch

To deploy everything from scratch:

```bash
# 1. Destroy existing stacks (WARNING: Deletes all data!)
cdk destroy --all

# 2. Set environment variables
export GEMINI_API_KEY="your-key-here"

# 3. Build all lambdas
./build-python-lambdas.sh
mvn clean package -DskipTests

# 4. Deploy all stacks
cdk deploy --all

# 5. Verify configuration (see Verification section above)
```

## Testing the Pipeline

After deployment, test with a PDF:

```bash
# 1. Upload a test PDF to trigger the pipeline
aws s3 cp test_book.pdf s3://original-file-bucket272765753210/

# 2. Monitor Textract extraction
aws logs tail /aws/lambda/FileFlowStack-TextractExtractionLambda* --follow

# 3. Monitor TTS generation
aws logs tail /aws/lambda/FileFlowStack-TTSGenerationLambda* --follow

# 4. Check audio chunks being created
aws s3 ls s3://audio-chunks-bucket272765753210/ --recursive --human-readable

# 5. Monitor audio stitching
aws logs tail /aws/lambda/FileFlowStack-AudioStitchingLambda* --follow
```

## Architecture Notes

### Rate Limiting
- Gemini TTS API limit: 10,000 tokens/minute
- Reserved concurrency: 2 instances
- Max theoretical usage: 2 × 4,500 tokens = 9,000 tokens/min ✅
- Token-based delay: Each lambda waits proportionally before API call

### Processing Flow
1. PDF → S3 → Textract Extraction
2. Full text → Text Chunking (4,500 tokens each)
3. Text chunks → TTS Generation (parallel, rate limited)
4. Audio chunks → Audio Stitching (with crossfading)
5. Final audio → Processed bucket

### Scalability
- 50 chunks @ 6 min each = ~3 hours total
- 2 concurrent executions = ~25 chunks/hour
- Can adjust reserved concurrency if API quota increases
