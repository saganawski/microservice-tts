# Use NVIDIA PyTorch base image for GPU support
FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    ffmpeg \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Install Flash Attention for improved performance (optional)
# RUN pip install flash-attn --no-build-isolation || echo "Flash Attention not installed"

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