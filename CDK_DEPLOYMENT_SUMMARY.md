# CDK Deployment Summary

## Deployment Steps

### 1. Build Lambda Functions
```bash
# Build Python lambdas (installs dependencies)
./build-python-lambdas.sh

# Build Java lambdas
mvn clean package -DskipTests
```

### 2. Deploy with CDK
```bash
cdk deploy --all
```

## API Key Management

The Gemini API key is stored in **AWS Secrets Manager** under the name `microservice-tts/gemini-api-key`.

- The TTS Generation Lambda reads the key from Secrets Manager at cold start
- No environment variables or `.env` sourcing needed for the API key at deploy time
- IAM permissions are granted automatically by CDK

### Rotating the Key
```bash
aws secretsmanager update-secret \
  --secret-id microservice-tts/gemini-api-key \
  --secret-string '{"api_key":"YOUR_NEW_KEY"}'
```
No redeployment needed — the Lambda picks up the new key on next cold start.

## Verification Commands

```bash
# Check all queues exist
aws sqs list-queues | grep -E "(chunking|tts|stitch)"

# Check TTS Lambda environment (should NOT contain GEMINI_API_KEY)
aws lambda get-function-configuration \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text) \
  | jq '.Environment.Variables | keys'

# Check reserved concurrency
aws lambda get-function-concurrency \
  --function-name $(aws lambda list-functions --query "Functions[?contains(FunctionName, 'TTSGenerationLambda')].FunctionName" --output text)
```

## Current Configuration

| Component | Value |
|-----------|-------|
| TTS Model | gemini-2.5-pro-preview-tts |
| TTS Voice | Charon |
| Concurrency | 2 reserved |
| Timeout | 15 minutes |
| Memory | 2048 MB |
| Secret Name | microservice-tts/gemini-api-key |
