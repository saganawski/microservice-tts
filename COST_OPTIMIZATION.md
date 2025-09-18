# Cost Optimization Guide for VibeVoice TTS

## ⚠️ Important: Preventing Unnecessary Charges

**By default, the VibeVoice EC2 instance is NOT deployed** unless explicitly requested. This prevents accidental AWS charges.

## Cost Breakdown

### When Using OpenAI (Default)
- **EC2 Cost**: $0 (no infrastructure deployed)
- **TTS Cost**: ~$15 per 1M characters
- **Total**: Pay-per-use model

### When Using VibeVoice

| State | EC2 Compute | EBS Storage | Total Monthly |
|-------|------------|-------------|---------------|
| **Running** | $378/month | $8/month | **$386/month** |
| **Stopped** | $0 | $8/month | **$8/month** |
| **Not Deployed** | $0 | $0 | **$0** |

## Deployment Strategies

### 1. Development Only with OpenAI (DEFAULT)
```bash
# Deploy without VibeVoice - NO EC2 CHARGES
./deploy-vibevoice.sh development

# Cost: $0 infrastructure + OpenAI usage fees
```

### 2. Deploy VibeVoice But Keep It Stopped
```bash
# Deploy the infrastructure
./deploy-vibevoice.sh vibevoice-deploy

# Immediately stop to save money
./deploy-vibevoice.sh vibevoice-stop

# Cost: $8/month (EBS storage only)
```

### 3. On-Demand VibeVoice Usage
```bash
# Start when needed
./deploy-vibevoice.sh switch-to-vibevoice  # Starts EC2 & switches Lambda

# Stop when done
./deploy-vibevoice.sh switch-to-openai      # Stops EC2 & switches to OpenAI

# Cost: Pay only for hours used
```

## Cost Management Commands

### Check Current Status
```bash
./deploy-vibevoice.sh vibevoice-status
```
Shows:
- Whether VibeVoice is deployed
- EC2 instance state (running/stopped)
- Current Lambda configuration
- Real-time cost indication

### Stop Instance (Save $378/month)
```bash
./deploy-vibevoice.sh vibevoice-stop
```
- Stops EC2 compute charges immediately
- Keeps EBS volume ($8/month)
- Can restart anytime

### Completely Remove VibeVoice
```bash
./deploy-vibevoice.sh vibevoice-destroy
```
- Deletes all VibeVoice resources
- Reduces cost to $0
- Requires redeployment to use again

## Automatic Cost Controls

### 1. No Accidental Deployment
- VibeVoice stack requires `DEPLOY_VIBEVOICE=true`
- Normal `cdk deploy` won't create EC2 instance

### 2. Provider Switching Stops/Starts EC2
```bash
# This automatically STOPS the EC2 instance
./deploy-vibevoice.sh switch-to-openai

# This automatically STARTS the EC2 instance
./deploy-vibevoice.sh switch-to-vibevoice
```

### 3. Instance State Management
- Instance stopped = No compute charges
- Instance running = Full charges apply
- EBS persists when stopped = Fast restarts

## Cost Optimization Tips

### 1. Use OpenAI for Low Volume
- If processing < 1.3M characters/month
- OpenAI is cheaper than VibeVoice EC2

### 2. Batch Processing with VibeVoice
```bash
# Start VibeVoice
./deploy-vibevoice.sh switch-to-vibevoice

# Process all your files

# Stop when done
./deploy-vibevoice.sh switch-to-openai
```

### 3. Schedule-Based Usage
Consider adding CloudWatch Events to:
- Start EC2 at 9 AM
- Stop EC2 at 6 PM
- Save ~60% on compute costs

### 4. Spot Instances (Advanced)
Modify `VibeVoiceStack.java` to use spot instances:
- Up to 70% savings
- Good for batch processing
- Risk of interruption

## Monthly Cost Scenarios

### Scenario 1: Pure OpenAI
- Setup: Never deploy VibeVoice
- Cost: $0 + OpenAI usage
- Best for: < 1.3M chars/month

### Scenario 2: Standby VibeVoice
- Setup: Deploy but keep stopped
- Cost: $8/month
- Best for: Occasional high-volume needs

### Scenario 3: Business Hours Only
- Setup: Run 8 hours/day, weekdays
- Cost: ~$84/month (22 days × 8 hours × $0.526)
- Best for: Regular business processing

### Scenario 4: Full Production
- Setup: 24/7 VibeVoice
- Cost: $386/month
- Best for: > 2M chars/month

## Monitoring Costs

### AWS Cost Explorer
```bash
# View EC2 costs
aws ce get-cost-and-usage \
  --time-period Start=2024-01-01,End=2024-01-31 \
  --granularity DAILY \
  --metrics "UnblendedCost" \
  --filter file://ec2-filter.json
```

### CloudWatch Alarms
```bash
# Create billing alarm at $50
aws cloudwatch put-metric-alarm \
  --alarm-name vibevoice-cost-alarm \
  --alarm-description "Alert when VibeVoice costs exceed $50" \
  --metric-name EstimatedCharges \
  --namespace AWS/Billing \
  --statistic Maximum \
  --period 86400 \
  --threshold 50 \
  --comparison-operator GreaterThanThreshold
```

## Quick Decision Tree

```
Daily volume < 40K chars?
  → Use OpenAI only

Daily volume > 40K chars but sporadic?
  → Deploy VibeVoice, start/stop as needed

Daily volume > 40K chars and consistent?
  → Run VibeVoice during business hours

Daily volume > 100K chars?
  → Run VibeVoice 24/7
```

## Emergency Cost Control

If you see unexpected charges:

1. **Immediate Stop**:
   ```bash
   ./deploy-vibevoice.sh vibevoice-stop
   ```

2. **Check Status**:
   ```bash
   ./deploy-vibevoice.sh vibevoice-status
   ```

3. **Destroy if Needed**:
   ```bash
   ./deploy-vibevoice.sh vibevoice-destroy
   ```

## Summary

✅ **No EC2 deployed by default** - Prevents accidental charges
✅ **Easy start/stop** - Control costs per hour
✅ **Automatic switching** - OpenAI fallback included
✅ **Clear cost visibility** - Status command shows real-time costs
✅ **Multiple strategies** - Choose based on your usage patterns

Remember: **You're only charged for EC2 when it's RUNNING!**