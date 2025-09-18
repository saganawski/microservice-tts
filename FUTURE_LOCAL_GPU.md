# Future Local GPU Setup Guide

## When You Upgrade Your Laptop GPU

This guide is saved for when you upgrade to a laptop with better GPU (6GB+ VRAM).

## 📊 GPU Requirements for Local VibeVoice

### Minimum GPUs for VibeVoice 1.5B
- **NVIDIA RTX 3050 (6GB)** - Minimum viable
- **NVIDIA RTX 3060 (12GB)** - Recommended
- **NVIDIA RTX 4050/4060 (8GB)** - Good performance
- **NVIDIA RTX 4070/4080 (12-16GB)** - Excellent

### For VibeVoice 7B Model
- **NVIDIA RTX 3090 (24GB)**
- **NVIDIA RTX 4090 (24GB)**
- **NVIDIA RTX A5000 (24GB)**

## 🚀 Local Setup (When You Have 6GB+ VRAM)

### 1. Verify Your New GPU
```bash
# Check GPU and VRAM
nvidia-smi

# Run our test script
./test-local-requirements.sh
```

### 2. Install CUDA and PyTorch
```bash
# Install CUDA-enabled PyTorch
pip install torch torchvision torchaudio

# Verify CUDA works
python -c "import torch; print(f'CUDA: {torch.cuda.is_available()}')"
python -c "import torch; print(f'GPU: {torch.cuda.get_device_name(0)}')"
python -c "import torch; print(f'VRAM: {torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f}GB')"
```

### 3. Run VibeVoice Locally
```bash
# Start local VibeVoice with Docker
./deploy-vibevoice.sh vibevoice-local

# Test it
curl -X POST http://localhost:8000/tts \
  -H "Content-Type: application/json" \
  -d '{"text": "Testing VibeVoice on my new GPU!"}' \
  --output test.wav

# Play the result
afplay test.wav  # Mac
aplay test.wav   # Linux
```

### 4. Configure Lambda to Use Local Instance
```bash
# Get your local IP
ip addr show | grep inet

# Update Lambda to use your local VibeVoice
aws lambda update-function-configuration \
  --function-name FileFlowStack-TTSLambda \
  --environment Variables="{TTS_PROVIDER=VIBEVOICE,VIBEVOICE_ENDPOINT_URL=http://YOUR_LOCAL_IP:8000}"
```

## 🎯 Performance Expectations by GPU

| GPU | VRAM | VibeVoice 1.5B Speed | VibeVoice 7B |
|-----|------|---------------------|--------------|
| RTX 3050 | 6GB | ~3x realtime | ❌ Can't run |
| RTX 3060 | 12GB | ~5x realtime | ❌ Can't run |
| RTX 3070 | 8GB | ~5x realtime | ❌ Can't run |
| RTX 3080 | 10-12GB | ~7x realtime | ❌ Can't run |
| RTX 3090 | 24GB | ~10x realtime | ✅ 2x realtime |
| RTX 4060 | 8GB | ~6x realtime | ❌ Can't run |
| RTX 4070 | 12GB | ~8x realtime | ❌ Can't run |
| RTX 4080 | 16GB | ~10x realtime | ⚠️ With quantization |
| RTX 4090 | 24GB | ~12x realtime | ✅ 3x realtime |

## 💰 Cost Comparison: Local vs EC2

### Running Locally (After GPU Upgrade)
- **Initial Cost**: $600-2000 (GPU upgrade)
- **Running Cost**: ~$0.10/month (electricity)
- **Speed**: Depends on GPU
- **Availability**: 24/7

### Running on EC2
- **Initial Cost**: $0
- **Running Cost**: $0.526/hour (~$378/month if 24/7)
- **Speed**: Consistent (T4 GPU)
- **Availability**: On-demand

### Break-even Analysis
```
New GPU Cost / Monthly EC2 Cost = Months to break even

Examples:
- RTX 3060 ($600) / $378 = 1.6 months
- RTX 4070 ($1200) / $378 = 3.2 months
- RTX 4090 ($2000) / $378 = 5.3 months
```

## 🔧 Optimization Tips for Local Running

### 1. Use Quantization for Better Performance
```python
# In server.py, already configured:
USE_QUANTIZATION=true  # 4-bit quantization
```

### 2. Batch Processing
```bash
# Process multiple files at once
for file in *.md; do
    curl -X POST http://localhost:8000/tts \
      -H "Content-Type: application/json" \
      -d "{\"text\": \"$(cat $file)\"}" \
      --output "${file%.md}.wav"
done
```

### 3. GPU Memory Management
```bash
# Monitor GPU usage
watch -n 1 nvidia-smi

# Clear GPU cache if needed
python -c "import torch; torch.cuda.empty_cache()"
```

## 📝 Notes for Your Current Setup (GTX 1050 Ti)

Your current GPU has **4GB VRAM**, which is below the 6GB minimum for VibeVoice.

**Options until upgrade:**
1. ✅ Use **OpenAI API** (recommended)
2. ✅ Use **EC2** for VibeVoice testing
3. ⚠️ Try CPU-only (extremely slow, not recommended)

**When to upgrade:**
- If you process >2M characters/month
- If you need offline TTS capability
- If you want instant local processing

## 🎮 Gaming GPUs That Work Well

If you're also a gamer, these GPUs are great for both gaming AND VibeVoice:

| GPU | Gaming | VibeVoice 1.5B | Price Range |
|-----|--------|---------------|-------------|
| RTX 3060 12GB | 1080p Ultra | ✅ Excellent | $350-450 |
| RTX 4060 Ti 16GB | 1440p Ultra | ✅ Excellent | $500-600 |
| RTX 4070 12GB | 1440p Ultra | ✅ Excellent | $550-650 |
| RTX 4070 Ti 12GB | 4K High | ✅ Excellent | $750-850 |

## 📅 Saved for Future You

This guide will be here when you're ready to upgrade. For now:
- Use **OpenAI** for development
- Use **EC2** for VibeVoice testing
- **Stop EC2** when not using it!

Good luck with your future GPU! 🚀