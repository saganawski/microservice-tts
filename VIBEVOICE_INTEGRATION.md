# VibeVoice TTS Integration

## Overview

This document describes the integration of Microsoft VibeVoice 1.5B model as an alternative TTS provider to OpenAI. The system is designed to be **provider-agnostic**, allowing easy switching between OpenAI and VibeVoice through environment configuration.

## Architecture

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────┐
│  S3 Event   │ ---> │  TTS Lambda  │ ---> │  TTS Provider   │
│  (Markdown) │      │  (Agnostic)  │      │ ┌─────────────┐ │
└─────────────┘      └──────────────┘      │ │   OpenAI    │ │
                            │               │ └─────────────┘ │
                            │               │ ┌─────────────┐ │
                     Environment Variables  │ │  VibeVoice  │ │
                     TTS_PROVIDER=OPENAI    │ │  (EC2/GPU)  │ │
                     or VIBEVOICE           │ └─────────────┘ │
                                           └─────────────────┘
```

## Key Features

### Provider Abstraction
- **Strategy Pattern**: TTS providers implement common interface
- **Runtime Selection**: Provider chosen based on `TTS_PROVIDER` environment variable
- **Zero Code Changes**: Switch providers without modifying Lambda code

### Provider Comparison

| Feature | OpenAI | VibeVoice 1.5B |
|---------|--------|----------------|
| **Chunk Size** | 900 chars | 20,000 chars |
| **Cost** | ~$15/1M chars | $378/month (fixed) |
| **Speed** | Fast API | 5x realtime |
| **Quality (MOS)** | ~4.2 | 4.3 ± 0.1 |
| **Deployment** | API Key only | EC2 g4dn.xlarge |
| **Multi-speaker** | No | Yes (up to 4) |

## Implementation Components

### 1. Lambda Provider Abstraction

```
lambdas/file-tts-lambda/
├── src/main/java/com/myorg/tts/
│   ├── TtsProvider.java          # Interface
│   ├── TtsProviderFactory.java   # Factory pattern
│   └── providers/
│       ├── OpenAiTtsProvider.java
│       ├── SelfHostedTtsProvider.java
│       └── VibeVoiceProvider.java
```

### 2. VibeVoice Service

```
vibevoice-service/
├── server.py              # Flask REST API
├── vibevoice_model.py     # Model integration
├── requirements.txt       # Python dependencies
├── Dockerfile            # Container definition
└── docker-compose.yml    # Local testing setup
```

### 3. Infrastructure (CDK)

```
cdk/src/main/java/com/myorg/stacks/
└── VibeVoiceStack.java   # EC2 deployment stack
```

## Deployment Options

### Option 1: Development (OpenAI Only)

```bash
# Deploy with OpenAI as default provider
./deploy-vibevoice.sh development

# Lambda uses OpenAI automatically
# No VibeVoice infrastructure deployed
```

### Option 2: Local VibeVoice Testing

```bash
# Run VibeVoice locally for testing
./deploy-vibevoice.sh vibevoice-local

# Service runs at http://localhost:8000
# Configure Lambda to use local endpoint for testing
```

### Option 3: Production VibeVoice

```bash
# Deploy VibeVoice to AWS EC2
./deploy-vibevoice.sh vibevoice-deploy

# Automatically:
# - Deploys EC2 g4dn.xlarge instance
# - Installs Docker and NVIDIA drivers
# - Runs VibeVoice container
# - Configures Lambda to use VibeVoice
```

## Configuration

### Lambda Environment Variables

```bash
# For OpenAI (default)
TTS_PROVIDER=OPENAI
OPENAI_API_KEY=sk-xxx

# For VibeVoice
TTS_PROVIDER=VIBEVOICE
VIBEVOICE_ENDPOINT_URL=http://ec2-ip:8000

# Optional configuration
TTS_VOICE=Speaker0
TTS_CHUNK_SIZE=900  # Override default chunk size
```

### Switching Providers

```bash
# Switch to VibeVoice
./deploy-vibevoice.sh switch-to-vibevoice

# Switch back to OpenAI
./deploy-vibevoice.sh switch-to-openai
```

## Testing

### 1. Test OpenAI Provider
```bash
# Ensure OpenAI is configured
export TTS_PROVIDER=OPENAI
mvn test
```

### 2. Test VibeVoice Locally
```bash
# Start VibeVoice service
cd vibevoice-service
docker-compose up

# Test endpoint
curl -X POST http://localhost:8000/tts \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello from VibeVoice"}' \
  --output test.wav
```

### 3. End-to-End Test
```bash
# Upload a markdown file to trigger the pipeline
aws s3 cp test.md s3://markdown-bucket/test.md

# Check processed bucket for audio output
aws s3 ls s3://processed-bucket/
```

## Cost Analysis

### Development Phase (3 months)
- **OpenAI**: ~$300/month (variable)
- **VibeVoice 1.5B**: $378/month (fixed)
- **Savings**: Predictable costs, no per-character charges

### Production Considerations
- **Break-even**: ~20M characters/month
- **Spot Instances**: Additional 70% savings possible
- **Auto-scaling**: Can scale down during low usage

## Monitoring

### CloudWatch Metrics
- TTS provider type
- Processing time per chunk
- Error rates by provider
- Cost tracking

### Logs
```bash
# View Lambda logs
aws logs tail /aws/lambda/FileFlowStack-TTSLambda --follow

# View VibeVoice EC2 logs
ssh ec2-user@<instance-ip>
docker logs vibevoice-service
```

## Troubleshooting

### Common Issues

1. **Lambda Timeout**
   - Increase Lambda timeout (currently 5 minutes)
   - VibeVoice chunks are larger but process faster

2. **EC2 GPU Not Available**
   - Ensure using Deep Learning AMI
   - Check NVIDIA drivers: `nvidia-smi`

3. **Provider Switch Not Working**
   - Verify environment variables
   - Check Lambda configuration
   - Review CloudWatch logs

## Future Enhancements

1. **Multi-speaker Support**: Implement dialogue detection
2. **Voice Cloning**: Add custom voice prompts
3. **7B Model Upgrade**: Switch to higher quality model
4. **Caching**: Cache frequently used text chunks
5. **Load Balancing**: Multiple EC2 instances for scale

## Security Considerations

- EC2 instance in private subnet (production)
- IAM roles with minimal permissions
- Secrets Manager for API keys
- VPC endpoints for S3 access
- Security group restrictions

## Summary

The VibeVoice integration provides:
- ✅ **Provider flexibility**: Easy switching between TTS providers
- ✅ **Cost control**: Fixed monthly costs vs per-character billing
- ✅ **Better chunking**: 20x larger chunks for natural speech
- ✅ **Future-proof**: Support for multiple TTS providers
- ✅ **Development friendly**: Test locally before deploying

The system maintains **backward compatibility** with OpenAI while enabling gradual migration to self-hosted VibeVoice when ready.