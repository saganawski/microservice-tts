# VibeVoice EC2 Quick Start Guide

## 🚀 Zero to VibeVoice in 15 Minutes

### Prerequisites
- AWS account with EC2 access
- AWS CLI configured (`aws configure`)
- Project built (`mvn clean package`)

## Step-by-Step EC2 Deployment

### 1️⃣ Deploy Infrastructure (One Time)
```bash
# Deploy VibeVoice to EC2 (takes ~10 minutes)
./deploy-vibevoice.sh vibevoice-deploy

# IMMEDIATELY stop it to save money
./deploy-vibevoice.sh vibevoice-stop
```

**Cost while stopped: $8/month (EBS storage only)**

### 2️⃣ When You Want to Test VibeVoice

```bash
# Start EC2 and switch Lambda to VibeVoice
./deploy-vibevoice.sh switch-to-vibevoice

# Upload a test file
echo "Hello from VibeVoice on AWS EC2" > test.md
aws s3 cp test.md s3://your-markdown-bucket/

# Check the output
aws s3 ls s3://your-processed-bucket/
aws s3 cp s3://your-processed-bucket/test.wav ./
# Play: afplay test.wav (Mac) or aplay test.wav (Linux)

# STOP when done (important!)
./deploy-vibevoice.sh switch-to-openai
```

### 3️⃣ Monitor Your Costs

```bash
# Check status anytime
./deploy-vibevoice.sh vibevoice-status

# Output will show:
# - Instance state (running = costing money)
# - Current provider (OpenAI or VibeVoice)
# - Hourly cost if running
```

## 💰 Cost Control Checklist

| Action | Command | Cost Impact |
|--------|---------|-------------|
| Deploy infrastructure | `vibevoice-deploy` | Creates resources |
| Stop EC2 | `vibevoice-stop` or `switch-to-openai` | Saves $378/month |
| Start EC2 | `switch-to-vibevoice` | Costs $0.526/hour |
| Check status | `vibevoice-status` | Free |
| Destroy everything | `vibevoice-destroy` | Removes all costs |

## ⏱️ Typical Testing Session

```bash
# Morning: Start testing
./deploy-vibevoice.sh switch-to-vibevoice  # Starts EC2

# Do your testing...
# Process files, compare with OpenAI, etc.

# Evening: Stop to save money
./deploy-vibevoice.sh switch-to-openai      # Stops EC2

# Total cost: ~$4 for 8 hours of testing
```

## 🔄 Provider Comparison Testing

```bash
# Test with OpenAI
./deploy-vibevoice.sh switch-to-openai
aws s3 cp test.md s3://markdown-bucket/test-openai.md
# Wait for processing...

# Test with VibeVoice
./deploy-vibevoice.sh switch-to-vibevoice
aws s3 cp test.md s3://markdown-bucket/test-vibevoice.md
# Wait for processing...

# Compare results
aws s3 cp s3://processed-bucket/test-openai.wav ./
aws s3 cp s3://processed-bucket/test-vibevoice.wav ./
```

## 🛑 Emergency Stop

If you forget to stop the EC2:

```bash
# Quick stop
./deploy-vibevoice.sh vibevoice-stop

# Or via AWS Console
# EC2 -> Instances -> Select instance -> Stop
```

## 📊 EC2 Instance Details

| Spec | Value | Why |
|------|-------|-----|
| **Type** | g4dn.xlarge | Cheapest GPU instance |
| **GPU** | NVIDIA T4 (16GB) | Handles 1.5B model well |
| **Cost** | $0.526/hour | ~$378/month if 24/7 |
| **Region** | Your default | Change in CDK if needed |

## 🔧 Troubleshooting

### VibeVoice Not Working?
```bash
# Check EC2 is running
./deploy-vibevoice.sh vibevoice-status

# Check Lambda configuration
aws lambda get-function-configuration \
  --function-name FileFlowStack-TTSLambda \
  --query 'Environment.Variables'

# Check EC2 logs
INSTANCE_ID=$(./deploy-vibevoice.sh vibevoice-status | grep "Instance ID" | awk '{print $3}')
aws ssm start-session --target $INSTANCE_ID
docker logs vibevoice-service
```

### Costs Too High?
```bash
# Check how long EC2 has been running
aws ec2 describe-instances \
  --instance-ids $INSTANCE_ID \
  --query 'Reservations[0].Instances[0].LaunchTime'

# Calculate cost
# Hours running × $0.526 = Your cost
```

## 📝 Best Practices

1. **Always stop when done**: The #1 rule
2. **Set calendar reminder**: To stop EC2 at end of day
3. **Use spot instances**: For 70% savings (advanced)
4. **Process in batches**: Start EC2, process all files, stop
5. **Monitor AWS billing**: Set up billing alerts

## 🎯 Quick Commands Reference

```bash
# Most used commands
./deploy-vibevoice.sh switch-to-openai     # Stop EC2, use OpenAI
./deploy-vibevoice.sh switch-to-vibevoice  # Start EC2, use VibeVoice
./deploy-vibevoice.sh vibevoice-status     # Check what's running
./deploy-vibevoice.sh vibevoice-stop       # Emergency stop
```

## 💡 Pro Tips

- **Test during AWS free tier hours** if applicable
- **Use CloudWatch** to auto-stop after 1 hour idle
- **Tag your resources** for cost tracking
- **Consider Reserved Instances** if using frequently

Remember: **EC2 only costs money when RUNNING!** Stop it when not in use.