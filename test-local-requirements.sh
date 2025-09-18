#!/bin/bash

# VibeVoice Local Requirements Checker
# This script tests if your local machine can run VibeVoice models

echo "======================================="
echo "VibeVoice Local Requirements Checker"
echo "======================================="
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Track if system meets requirements
CAN_RUN_1_5B=true
CAN_RUN_7B=true

# 1. Check Operating System
echo "1. Checking Operating System..."
OS_TYPE=$(uname -s)
if [[ "$OS_TYPE" == "Linux" ]] || [[ "$OS_TYPE" == "Darwin" ]]; then
    echo -e "${GREEN}✓ OS: $OS_TYPE${NC}"
else
    echo -e "${YELLOW}⚠ OS: $OS_TYPE (Windows requires WSL2)${NC}"
fi
echo ""

# 2. Check Python
echo "2. Checking Python..."
if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
    echo -e "${GREEN}✓ Python: $PYTHON_VERSION${NC}"
else
    echo -e "${RED}✗ Python 3 not found${NC}"
    CAN_RUN_1_5B=false
    CAN_RUN_7B=false
fi
echo ""

# 3. Check Docker
echo "3. Checking Docker..."
if command -v docker &> /dev/null; then
    DOCKER_VERSION=$(docker --version | awk '{print $3}' | sed 's/,//')
    echo -e "${GREEN}✓ Docker: $DOCKER_VERSION${NC}"

    # Check if Docker daemon is running
    if docker info &> /dev/null; then
        echo -e "${GREEN}✓ Docker daemon is running${NC}"
    else
        echo -e "${YELLOW}⚠ Docker daemon is not running. Start with: sudo service docker start${NC}"
    fi
else
    echo -e "${YELLOW}⚠ Docker not found (optional but recommended)${NC}"
fi
echo ""

# 4. Check NVIDIA GPU
echo "4. Checking GPU..."
HAS_GPU=false
if command -v nvidia-smi &> /dev/null; then
    GPU_INFO=$(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null)
    if [ $? -eq 0 ]; then
        HAS_GPU=true
        echo -e "${GREEN}✓ NVIDIA GPU detected:${NC}"
        echo "  $GPU_INFO"

        # Get VRAM in MB
        VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null | head -1)
        VRAM_GB=$((VRAM_MB / 1024))

        echo "  Total VRAM: ${VRAM_GB}GB"

        # Check CUDA
        if command -v nvcc &> /dev/null; then
            CUDA_VERSION=$(nvcc --version | grep "release" | awk '{print $6}' | sed 's/,//')
            echo -e "${GREEN}✓ CUDA: $CUDA_VERSION${NC}"
        else
            echo -e "${YELLOW}⚠ CUDA toolkit not found (PyTorch includes CUDA libs)${NC}"
        fi

        # Check if VRAM is sufficient
        if [ $VRAM_MB -lt 6000 ]; then
            echo -e "${RED}✗ Insufficient VRAM for VibeVoice 1.5B (need 6GB+)${NC}"
            CAN_RUN_1_5B=false
            CAN_RUN_7B=false
        elif [ $VRAM_MB -lt 18000 ]; then
            echo -e "${YELLOW}⚠ Can run 1.5B model but not 7B (need 18GB+ for 7B)${NC}"
            CAN_RUN_7B=false
        else
            echo -e "${GREEN}✓ Sufficient VRAM for both 1.5B and 7B models${NC}"
        fi
    else
        echo -e "${YELLOW}⚠ NVIDIA driver installed but GPU not accessible${NC}"
        HAS_GPU=false
    fi
else
    echo -e "${YELLOW}⚠ No NVIDIA GPU detected - will use CPU (very slow)${NC}"

    # For Mac, check for Apple Silicon
    if [[ "$OS_TYPE" == "Darwin" ]]; then
        if [[ $(sysctl -n machdep.cpu.brand_string) == *"Apple"* ]]; then
            echo -e "${GREEN}✓ Apple Silicon detected - can use MPS acceleration${NC}"
            HAS_GPU=true  # MPS can work as GPU alternative
        fi
    fi
fi
echo ""

# 5. Check RAM
echo "5. Checking System RAM..."
if [[ "$OS_TYPE" == "Darwin" ]]; then
    # macOS
    TOTAL_RAM_GB=$(($(sysctl -n hw.memsize) / 1024 / 1024 / 1024))
else
    # Linux
    TOTAL_RAM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
    TOTAL_RAM_GB=$((TOTAL_RAM_KB / 1024 / 1024))
fi

echo "  Total RAM: ${TOTAL_RAM_GB}GB"

if [ $TOTAL_RAM_GB -lt 16 ]; then
    echo -e "${RED}✗ Insufficient RAM (need 16GB minimum)${NC}"
    CAN_RUN_1_5B=false
    CAN_RUN_7B=false
elif [ $TOTAL_RAM_GB -lt 32 ]; then
    echo -e "${YELLOW}⚠ Can run 1.5B model but may struggle with 7B${NC}"
    CAN_RUN_7B=false
else
    echo -e "${GREEN}✓ Sufficient RAM for both models${NC}"
fi
echo ""

# 6. Check Disk Space
echo "6. Checking Disk Space..."
AVAILABLE_GB=$(df -BG . | awk 'NR==2 {print $4}' | sed 's/G//')
echo "  Available disk space: ${AVAILABLE_GB}GB"

if [ $AVAILABLE_GB -lt 20 ]; then
    echo -e "${RED}✗ Insufficient disk space (need 20GB+ for models)${NC}"
    CAN_RUN_1_5B=false
    CAN_RUN_7B=false
elif [ $AVAILABLE_GB -lt 50 ]; then
    echo -e "${YELLOW}⚠ Limited disk space, can run 1.5B model${NC}"
    CAN_RUN_7B=false
else
    echo -e "${GREEN}✓ Sufficient disk space${NC}"
fi
echo ""

# 7. Test PyTorch Installation
echo "7. Checking PyTorch..."
python3 -c "import torch; print(f'✓ PyTorch {torch.__version__} installed')" 2>/dev/null
if [ $? -ne 0 ]; then
    echo -e "${YELLOW}⚠ PyTorch not installed. Install with:${NC}"
    if [ "$HAS_GPU" = true ]; then
        echo "  pip install torch torchvision torchaudio"
    else
        echo "  pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu"
    fi
fi

# Check if PyTorch can see GPU
if [ "$HAS_GPU" = true ]; then
    python3 -c "import torch; print(f'  CUDA available: {torch.cuda.is_available()}')" 2>/dev/null
    python3 -c "import torch; print(f'  CUDA device: {torch.cuda.get_device_name(0) if torch.cuda.is_available() else \"None\"}')" 2>/dev/null
fi
echo ""

# 8. Network Speed Test (optional)
echo "8. Checking Network (for model download)..."
# Test connection to Hugging Face
if curl -s --head https://huggingface.co | head -n 1 | grep "200" > /dev/null; then
    echo -e "${GREEN}✓ Can connect to Hugging Face${NC}"

    # Estimate download time
    echo "  Model download sizes:"
    echo "  - VibeVoice 1.5B: ~5.4GB"
    echo "  - VibeVoice 7B: ~18GB"
else
    echo -e "${YELLOW}⚠ Cannot connect to Hugging Face (needed for model download)${NC}"
fi
echo ""

# Summary and Recommendations
echo "======================================="
echo "SUMMARY & RECOMMENDATIONS"
echo "======================================="
echo ""

if [ "$CAN_RUN_1_5B" = true ]; then
    echo -e "${GREEN}✓ Your system CAN run VibeVoice 1.5B locally${NC}"

    if [ "$HAS_GPU" = true ]; then
        echo "  Performance: Good (GPU acceleration available)"
        echo "  Expected speed: ~5x realtime"
    else
        echo "  Performance: Poor (CPU only - very slow)"
        echo "  Expected speed: ~0.1x realtime (10x slower than real-time)"
        echo -e "${YELLOW}  ⚠ CPU inference is not recommended for production${NC}"
    fi
else
    echo -e "${RED}✗ Your system CANNOT run VibeVoice 1.5B locally${NC}"
    echo "  Missing requirements listed above"
fi
echo ""

if [ "$CAN_RUN_7B" = true ]; then
    echo -e "${GREEN}✓ Your system CAN run VibeVoice 7B locally${NC}"
    echo "  Performance: Good (sufficient resources)"
else
    echo -e "${YELLOW}⚠ Your system CANNOT run VibeVoice 7B locally${NC}"
    echo "  Consider using 1.5B model or cloud deployment"
fi
echo ""

# Provide specific recommendations
echo "RECOMMENDATIONS:"
echo "----------------"

if [ "$HAS_GPU" = false ] && [ "$CAN_RUN_1_5B" = true ]; then
    echo "1. CPU-only inference will be VERY slow"
    echo "   - Consider using AWS EC2 with GPU instead"
    echo "   - Or use OpenAI API for better performance"
fi

if [ "$CAN_RUN_1_5B" = true ]; then
    echo ""
    echo "To test VibeVoice locally:"
    echo "  1. ./deploy-vibevoice.sh vibevoice-local"
    echo "  2. Wait for model download (first time only)"
    echo "  3. Test with: curl -X POST http://localhost:8000/tts \\"
    echo "       -H 'Content-Type: application/json' \\"
    echo "       -d '{\"text\": \"Hello from VibeVoice\"}' \\"
    echo "       --output test.wav"
else
    echo ""
    echo "Your system doesn't meet minimum requirements."
    echo "Options:"
    echo "  1. Use OpenAI API (no local requirements)"
    echo "  2. Deploy to AWS EC2 with GPU"
    echo "  3. Upgrade your local hardware"
fi

echo ""
echo "======================================="

# Quick performance test (optional)
if [ "$CAN_RUN_1_5B" = true ] && [ "$HAS_GPU" = true ]; then
    echo ""
    read -p "Run a quick PyTorch performance test? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Running performance test..."
        python3 << EOF
import torch
import time

# Test matrix multiplication performance
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Testing on: {device}")

# Create test matrices
size = 4096
a = torch.randn(size, size).to(device)
b = torch.randn(size, size).to(device)

# Warmup
for _ in range(3):
    c = torch.matmul(a, b)
    if device.type == 'cuda':
        torch.cuda.synchronize()

# Benchmark
start = time.time()
for _ in range(10):
    c = torch.matmul(a, b)
    if device.type == 'cuda':
        torch.cuda.synchronize()
end = time.time()

avg_time = (end - start) / 10
tflops = (2 * size**3 / avg_time) / 1e12

print(f"Average time per operation: {avg_time:.3f} seconds")
print(f"Performance: {tflops:.2f} TFLOPS")

if tflops > 5:
    print("✓ Excellent performance for TTS inference")
elif tflops > 1:
    print("✓ Good performance for TTS inference")
elif tflops > 0.1:
    print("⚠ Adequate performance (may be slow)")
else:
    print("✗ Poor performance (not recommended)")
EOF
    fi
fi