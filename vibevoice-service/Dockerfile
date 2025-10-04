# Use NVIDIA PyTorch base image for GPU support
FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

# Set working directory
WORKDIR /app

# Install system dependencies
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Clone and install VibeVoice from GitHub
RUN git clone https://github.com/microsoft/VibeVoice.git /tmp/vibevoice && \
    cd /tmp/vibevoice && \
    pip install --no-cache-dir -e . && \
    cd /app && \
    rm -rf /tmp/vibevoice

# Install Flash Attention for improved performance (optional, may take time to build)
RUN pip install flash-attn --no-build-isolation || echo "Flash Attention not installed, continuing without it"

# Copy application code
COPY server.py .
COPY vibevoice_model.py .

# Create directory for model cache
RUN mkdir -p /root/.cache/huggingface

# Set environment variables
ENV PYTHONUNBUFFERED=1
ENV CUDA_VISIBLE_DEVICES=0
ENV VIBEVOICE_MODEL=microsoft/VibeVoice-1.5B
ENV USE_QUANTIZATION=true
ENV PORT=8000

# Expose the service port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# Run the server
CMD ["python", "server.py"]