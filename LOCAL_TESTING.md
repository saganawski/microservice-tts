# Local VibeVoice Testing Guide

## Quick Check: Can Your Machine Run VibeVoice?

Run this command to check if your system meets the requirements:
```bash
./test-local-requirements.sh
```

## System Requirements

### Minimum Requirements (VibeVoice 1.5B)

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **GPU** | 6GB VRAM | 8GB+ VRAM |
| **RAM** | 16GB | 32GB |
| **Storage** | 20GB free | 50GB free |
| **CPU** | 4 cores | 8+ cores |
| **OS** | Linux/macOS/WSL2 | Ubuntu 20.04+ |

### For VibeVoice 7B Model

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **GPU** | 18GB VRAM | 24GB+ VRAM |
| **RAM** | 32GB | 64GB |
| **Storage** | 50GB free | 100GB free |

## GPU Compatibility

### ✅ Supported GPUs for 1.5B Model
- NVIDIA RTX 3060 (12GB)
- NVIDIA RTX 3070/3080/3090
- NVIDIA RTX 4070/4080/4090
- Apple M1/M2 Mac (using MPS)
- NVIDIA T4 (16GB)
- NVIDIA A10G (24GB)

### ✅ Supported GPUs for 7B Model
- NVIDIA RTX 3090 (24GB)
- NVIDIA RTX 4090 (24GB)
- NVIDIA A10G (24GB)
- NVIDIA A100 (40GB/80GB)

### ⚠️ Not Recommended
- GPUs with <6GB VRAM
- Intel integrated graphics
- AMD GPUs (limited PyTorch support)

## Running Locally

### Option 1: Docker (Recommended)

```bash
# 1. Start VibeVoice locally with Docker
./deploy-vibevoice.sh vibevoice-local

# 2. Check if service is running
curl http://localhost:8000/health

# 3. Test TTS generation
curl -X POST http://localhost:8000/tts \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello from VibeVoice running locally"}' \
  --output test.wav

# 4. Play the audio
# macOS: afplay test.wav
# Linux: aplay test.wav
```

### Option 2: Direct Python

```bash
# 1. Install dependencies
cd vibevoice-service
pip install -r requirements.txt

# 2. Run the server
python server.py

# 3. Test in another terminal
curl http://localhost:8000/health
```

## Performance Expectations

### With GPU (NVIDIA/Apple Silicon)
- **1.5B Model**: ~5x realtime (1 minute audio in 12 seconds)
- **7B Model**: ~1.25x realtime (1 minute audio in 48 seconds)

### Without GPU (CPU Only) ⚠️
- **1.5B Model**: ~0.1x realtime (1 minute audio in 10 minutes)
- **7B Model**: ~0.02x realtime (1 minute audio in 50 minutes)
- **NOT RECOMMENDED** for production use

## Optimization for Local Testing

### 1. Use 4-bit Quantization (Reduces VRAM by 75%)
```python
# Already enabled by default in our implementation
USE_QUANTIZATION=true
```

### 2. Reduce Batch Size
```bash
# For low VRAM systems
export BATCH_SIZE=1
```

### 3. Use CPU Offloading
```python
# For systems with limited VRAM
device_map="auto"  # Automatically offloads to CPU when needed
```

## Testing with Lambda

### Connect Lambda to Local VibeVoice

1. **Get your local IP address**:
```bash
# Linux
hostname -I | awk '{print $1}'

# macOS
ipconfig getifaddr en0
```

2. **Configure Lambda to use local endpoint**:
```bash
aws lambda update-function-configuration \
  --function-name FileFlowStack-TTSLambda \
  --environment Variables="{TTS_PROVIDER=VIBEVOICE,VIBEVOICE_ENDPOINT_URL=http://YOUR_LOCAL_IP:8000}"
```

3. **Important**: Ensure your firewall allows port 8000

## Troubleshooting Local Setup

### Issue: "CUDA out of memory"
```bash
# Solution 1: Use smaller model
export VIBEVOICE_MODEL=microsoft/VibeVoice-1.5B

# Solution 2: Enable more aggressive quantization
export USE_QUANTIZATION=true

# Solution 3: Reduce inference steps
export INFERENCE_STEPS=30  # Default is 50
```

### Issue: "Docker: no GPU detected"
```bash
# Check NVIDIA runtime
docker run --rm --gpus all nvidia/cuda:11.8.0-base-ubuntu22.04 nvidia-smi

# If fails, install nvidia-docker2
curl -fsSL https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | \
  sudo tee /etc/apt/sources.list.d/nvidia-docker.list
sudo apt-get update
sudo apt-get install -y nvidia-docker2
sudo systemctl restart docker
```

### Issue: "Model download taking forever"
```bash
# Models are large, first download can take time:
# - 1.5B model: ~5.4GB
# - 7B model: ~18GB

# Use faster mirror (if available)
export HF_ENDPOINT=https://hf-mirror.com

# Or pre-download the model
python -c "from transformers import AutoModel; AutoModel.from_pretrained('microsoft/VibeVoice-1.5B')"
```

## Local vs Cloud Decision Matrix

| Factor | Use Local | Use AWS EC2 |
|--------|-----------|-------------|
| **Volume** | < 10K chars/day | > 50K chars/day |
| **GPU Available** | Yes (6GB+) | No or weak GPU |
| **Response Time** | Not critical | Need consistent speed |
| **Cost Sensitivity** | Very high | Moderate |
| **Internet** | Slow/metered | Fast/unlimited |
| **Privacy** | Critical | Standard |

## Memory Usage Monitoring

```bash
# Monitor GPU memory during inference
watch -n 1 nvidia-smi

# Monitor system RAM
htop

# Monitor Docker container
docker stats vibevoice-service
```

## Quick Performance Test

```bash
# Test your local setup performance
echo "The quick brown fox jumps over the lazy dog. This is a test of the VibeVoice text-to-speech system running locally." > test.txt

time curl -X POST http://localhost:8000/tts \
  -H "Content-Type: application/json" \
  -d "{\"text\": \"$(cat test.txt)\"}" \
  --output performance_test.wav

# Check the time taken and file size
ls -lh performance_test.wav
```

## Summary

✅ **Run locally if you have**:
- NVIDIA GPU with 6GB+ VRAM
- 16GB+ RAM
- Don't mind initial model download

⚠️ **Use cloud (EC2) if you**:
- Don't have a GPU
- Need consistent performance
- Process large volumes regularly

❌ **Don't run locally if you**:
- Only have CPU (extremely slow)
- Have <16GB RAM
- Need real-time inference