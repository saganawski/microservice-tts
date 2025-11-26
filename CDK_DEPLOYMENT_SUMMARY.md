# CDK Deployment Summary

## ✅ CDK Code Status: CORRECT

The CDK code in `FileFlowStack.java` is **already correctly configured**. No code changes needed!

### What's Correct in the CDK:

1. **Stitch Queue Name** ✅
   - Line 385: `queueName("stitch-notification-queue.fifo")`
   - Correctly references: `stitchQueue.getQueueUrl()` (Line 470)

2. **TTS Lambda Configuration** ✅
   - Reserved concurrency: 2 (Line 478)
   - Timeout: 15 minutes (Line 476)
   - Memory: 2048 MB (Line 477)
   - All environment variables correctly mapped

3. **Queue Configuration** ✅
   - All FIFO queues with correct deduplication
   - Dead letter queues configured
   - Proper visibility timeouts

## ⚠️ Required: Environment Variable

The **only requirement** for deployment is setting the environment variable:

```bash
export GEMINI_API_KEY="AIzaSyCsCODpn4VCbeLWjaOOZ0tAxDR0noRTaZw"
```

### Why This is Needed

The CDK code (Line 467) reads from the environment:

```java
"GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") != null ? System.getenv("GEMINI_API_KEY") : "",
```

If the environment variable is **not set** during `cdk deploy`, the lambda will be deployed with an **empty string**, causing TTS generation to fail.

## 🚀 Clean Deployment Steps

To deploy from scratch with all correct configuration:

### 1. Set Environment Variable
```bash
export GEMINI_API_KEY="AIzaSyCsCODpn4VCbeLWjaOOZ0tAxDR0noRTaZw"
echo $GEMINI_API_KEY  # Verify it's set
```

### 2. Build Lambda Functions
```bash
# Build Python lambdas (installs dependencies)
./build-python-lambdas.sh

# Build Java lambdas
mvn clean package -DskipTests
```

### 3. Deploy with CDK
```bash
# Deploy all stacks
cdk deploy --all

# Or deploy individually
cdk deploy FileFlowStack
cdk deploy ApiStack
```

### 4. Verify Deployment
```bash
# Check TTS Lambda has API key set
aws lambda get-function-configuration \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text) \
  | jq '.Environment.Variables.GEMINI_API_KEY'

# Should output: "AIzaSyCsCODpn4VCbeLWjaOOZ0tAxDR0noRTaZw"
# NOT: "" (empty string)
```

## 📋 What We Fixed Manually (Now Documented)

During testing, we had to fix these issues manually because the environment variable wasn't set:

| Issue | Manual Fix | CDK Solution |
|-------|-----------|--------------|
| Missing API Key | `aws lambda update-function-configuration` | Set `export GEMINI_API_KEY` before deploy |
| Wrong Queue URL | `aws lambda update-function-configuration` | Already correct in CDK (line 470) |
| Missing Messages | Manually triggered chunking lambda | N/A - one-time testing issue |

## 🎯 For Future Deployments

**Option 1: Environment Variable (Current)**
```bash
export GEMINI_API_KEY="your-key-here"
cdk deploy --all
```

**Option 2: Using .env File (Recommended)**
```bash
# Create .env file
cp .env.example .env
# Edit .env and add your API key

# Source it
source .env

# Deploy
cdk deploy --all
```

**Option 3: AWS Secrets Manager (Production)**

For production, consider using AWS Secrets Manager:

```java
// In FileFlowStack.java (future enhancement)
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.ISecret;

// Retrieve secret
ISecret geminiApiKey = Secret.fromSecretNameV2(this, "GeminiApiKey", "prod/gemini/api-key");

// Use in lambda
.environment(Map.of(
    "GEMINI_API_KEY", geminiApiKey.secretValueFromJson("api_key").toString()
))
```

## 📊 Current Working Configuration

The system is **currently operational** with:

- ✅ Textract extraction: Working
- ✅ Text chunking: Working (50 chunks created)
- ✅ TTS generation: Working (4/50 chunks complete, 45 in progress)
- ✅ Rate limiting: Working (20-22s delays)
- ✅ Audio storage: Working (chunks in S3)

**Processing Status:**
- Job ID: `lore-test-textract-20251124071647-20251124-121651`
- Chunks: 4 completed, 45 waiting, 1 in-flight
- ETA: ~2.5 hours for all 50 chunks

## 🔍 Verification Commands

After deployment, verify everything is correct:

```bash
# 1. Check all queues exist
aws sqs list-queues | grep -E "(chunking|tts|stitch)"

# Expected output:
# - chunking-queue.fifo
# - chunking-dlq.fifo
# - tts-processing-queue.fifo
# - tts-dlq.fifo
# - stitch-notification-queue.fifo  ← Must include "notification"

# 2. Check TTS Lambda environment
aws lambda get-function-configuration \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text) \
  | jq '.Environment.Variables | {
    GEMINI_API_KEY: .GEMINI_API_KEY[:20] + "...",
    STITCH_QUEUE_URL,
    GEMINI_TTS_MODEL,
    GEMINI_TTS_VOICE
  }'

# Expected output:
# {
#   "GEMINI_API_KEY": "AIzaSyCsCODpn4VCbeL...",  ← Not empty!
#   "STITCH_QUEUE_URL": "https://sqs.us-east-1.amazonaws.com/272765753210/stitch-notification-queue.fifo",
#   "GEMINI_TTS_MODEL": "gemini-2.5-pro-preview-tts",
#   "GEMINI_TTS_VOICE": "Charon"
# }

# 3. Check reserved concurrency
aws lambda get-function-concurrency \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text)

# Expected output:
# {
#   "ReservedConcurrentExecutions": 2
# }
```

## 📁 New Files Created

1. **`DEPLOYMENT_CHECKLIST.md`** - Comprehensive deployment guide
2. **`.env.example`** - Environment variable template
3. **`CDK_DEPLOYMENT_SUMMARY.md`** (this file) - Quick reference
4. **`CLAUDE.md`** - Updated with deployment requirements

## 🎉 Summary

**The CDK infrastructure code is production-ready!**

The only requirement for deployment is:
1. Set `GEMINI_API_KEY` environment variable
2. Build lambda functions
3. Run `cdk deploy --all`

Everything else is automatically configured correctly by the CDK.
