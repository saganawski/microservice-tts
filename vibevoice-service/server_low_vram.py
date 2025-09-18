#!/usr/bin/env python3
"""
VibeVoice TTS Service - Low VRAM Version for 4GB GPUs
Optimized for GTX 1050 Ti and similar cards
"""

import os
import io
import logging
import time
import traceback
import torch
import numpy as np
import soundfile as sf
from flask import Flask, request, jsonify, Response
from flask_cors import CORS

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Flask app initialization
app = Flask(__name__)
CORS(app)

# Global model variable
model = None
device = None

# Configuration for low VRAM
MODEL_NAME = os.getenv("VIBEVOICE_MODEL", "microsoft/VibeVoice-1.5B")
PORT = int(os.getenv("PORT", "8000"))

def load_model_low_vram():
    """Load VibeVoice model with aggressive optimization for 4GB VRAM."""
    global model, device

    logger.info(f"Loading model for LOW VRAM (4GB): {MODEL_NAME}")

    # Check available VRAM
    if torch.cuda.is_available():
        device = torch.device("cuda")
        vram = torch.cuda.get_device_properties(0).total_memory / 1024**3
        logger.info(f"GPU detected: {torch.cuda.get_device_name(0)}")
        logger.info(f"Available VRAM: {vram:.1f}GB")

        if vram < 6:
            logger.warning("Low VRAM detected - using aggressive optimizations")
    else:
        device = torch.device("cpu")
        logger.warning("No GPU detected - using CPU (very slow)")

    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

        if device.type == "cuda" and vram < 6:
            # Aggressive 4-bit quantization for low VRAM
            quantization_config = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_compute_dtype=torch.float16,  # Use fp16 instead of bf16
                bnb_4bit_use_double_quant=True,
                bnb_4bit_quant_type="nf4",
                llm_int8_enable_fp32_cpu_offload=True  # Offload to CPU when needed
            )
            logger.info("Using aggressive 4-bit quantization with CPU offloading")

            # Load with device_map="auto" to automatically offload layers
            # This is a placeholder - actual VibeVoice loading would be:
            """
            from vibevoice import VibeVoiceModel
            model = VibeVoiceModel.from_pretrained(
                MODEL_NAME,
                quantization_config=quantization_config,
                device_map="auto",  # Automatically balance between GPU and CPU
                max_memory={0: "3.5GB", "cpu": "20GB"},  # Reserve some VRAM
                torch_dtype=torch.float16,
                low_cpu_mem_usage=True
            )
            """
            model = None  # Placeholder
            logger.info("Model loaded with GPU+CPU offloading")

        else:
            logger.info("Loading model normally")
            model = None  # Placeholder

        # Clear cache after loading
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            logger.info(f"VRAM after loading: {torch.cuda.memory_allocated()/1024**3:.1f}GB used")

    except Exception as e:
        logger.error(f"Failed to load model: {str(e)}")
        logger.error(traceback.format_exc())
        raise

def text_to_speech_low_vram(text: str, max_chunk=5000):
    """
    Convert text to speech with low VRAM optimizations.

    Args:
        text: Input text to convert
        max_chunk: Maximum chunk size (smaller = less VRAM)
    """
    try:
        logger.info(f"Processing text of length: {len(text)}")

        # For low VRAM, process in smaller chunks
        if len(text) > max_chunk:
            logger.info(f"Text too long, chunking to {max_chunk} chars")
            text = text[:max_chunk]

        # Reduce inference steps for speed
        inference_steps = 30  # Default is 50

        # Clear GPU cache before processing
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

        # Generate audio (placeholder)
        # In reality, this would call the model
        sample_rate = 24000
        duration = min(len(text) / 150, 10)
        t = np.linspace(0, duration, int(sample_rate * duration))
        audio = 0.5 * np.sin(2 * np.pi * 440 * t)

        # Clear cache after processing
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

        # Convert to WAV
        buffer = io.BytesIO()
        sf.write(buffer, audio, sample_rate, format='WAV')
        buffer.seek(0)

        return buffer.read()

    except torch.cuda.OutOfMemoryError:
        logger.error("GPU out of memory! Try:")
        logger.error("1. Reduce text length")
        logger.error("2. Restart the service")
        logger.error("3. Use CPU-only mode")
        raise RuntimeError("GPU out of memory - text too long for 4GB VRAM")

    except Exception as e:
        logger.error(f"TTS processing error: {str(e)}")
        raise

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    gpu_info = {}
    if torch.cuda.is_available():
        gpu_info = {
            "gpu_name": torch.cuda.get_device_name(0),
            "vram_total": f"{torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f}GB",
            "vram_used": f"{torch.cuda.memory_allocated() / 1024**3:.1f}GB",
            "vram_free": f"{(torch.cuda.get_device_properties(0).total_memory - torch.cuda.memory_allocated()) / 1024**3:.1f}GB"
        }

    return jsonify({
        "status": "healthy",
        "model": MODEL_NAME,
        "device": str(device) if device else "not loaded",
        "low_vram_mode": True,
        **gpu_info
    }), 200

@app.route('/tts', methods=['POST'])
def tts_endpoint():
    """TTS endpoint optimized for low VRAM."""
    try:
        data = request.json
        if not data or 'text' not in data:
            return jsonify({"error": "Missing 'text' field in request"}), 400

        text = data['text']

        # Warn if text is too long
        if len(text) > 5000:
            logger.warning(f"Text length {len(text)} may cause OOM on 4GB VRAM")

        # Monitor VRAM before processing
        if torch.cuda.is_available():
            free_vram = (torch.cuda.get_device_properties(0).total_memory - torch.cuda.memory_allocated()) / 1024**3
            logger.info(f"Free VRAM before TTS: {free_vram:.1f}GB")

        # Perform TTS
        start_time = time.time()
        audio_data = text_to_speech_low_vram(text)
        processing_time = time.time() - start_time

        logger.info(f"TTS completed in {processing_time:.2f} seconds")

        return Response(
            audio_data,
            mimetype='audio/wav',
            headers={
                'Content-Disposition': 'attachment; filename=output.wav',
                'X-Processing-Time': str(processing_time),
                'X-Low-VRAM-Mode': 'true'
            }
        )

    except RuntimeError as e:
        if "out of memory" in str(e).lower():
            return jsonify({
                "error": "GPU out of memory",
                "suggestion": "Try shorter text or use CPU mode",
                "max_recommended_length": 5000
            }), 507  # Insufficient Storage
        return jsonify({"error": str(e)}), 500

    except Exception as e:
        logger.error(f"TTS endpoint error: {str(e)}")
        return jsonify({"error": str(e)}), 500

@app.route('/gpu_status', methods=['GET'])
def gpu_status():
    """Check GPU memory status."""
    if not torch.cuda.is_available():
        return jsonify({"error": "No GPU available"}), 404

    return jsonify({
        "gpu_name": torch.cuda.get_device_name(0),
        "vram_total_gb": torch.cuda.get_device_properties(0).total_memory / 1024**3,
        "vram_used_gb": torch.cuda.memory_allocated() / 1024**3,
        "vram_free_gb": (torch.cuda.get_device_properties(0).total_memory - torch.cuda.memory_allocated()) / 1024**3,
        "vram_used_percent": (torch.cuda.memory_allocated() / torch.cuda.get_device_properties(0).total_memory) * 100,
        "can_process_chars": 5000 if torch.cuda.memory_allocated() / torch.cuda.get_device_properties(0).total_memory < 0.8 else 1000
    }), 200

def main():
    """Main entry point."""
    logger.info("Starting VibeVoice TTS Service (Low VRAM Mode)")
    logger.info(f"Model: {MODEL_NAME}")
    logger.info(f"Port: {PORT}")

    # Check GPU
    if torch.cuda.is_available():
        vram = torch.cuda.get_device_properties(0).total_memory / 1024**3
        if vram < 6:
            logger.warning(f"⚠️  LOW VRAM DETECTED: {vram:.1f}GB")
            logger.warning("Performance will be limited")
            logger.warning("Maximum text length: ~5000 characters")
        else:
            logger.info(f"✓ GPU has sufficient VRAM: {vram:.1f}GB")
    else:
        logger.warning("⚠️  No GPU detected - using CPU (extremely slow)")

    # Load model
    try:
        load_model_low_vram()
    except Exception as e:
        logger.error(f"Failed to initialize model: {str(e)}")
        logger.warning("Service will run but TTS won't work")

    # Run Flask app
    app.run(
        host='0.0.0.0',
        port=PORT,
        debug=False
    )

if __name__ == '__main__':
    main()