#!/usr/bin/env python3
"""
VibeVoice TTS Service
A Flask-based REST API for Microsoft VibeVoice text-to-speech conversion.
"""

import os
import io
import logging
import time
import traceback
from typing import Dict, Any, Optional
from flask import Flask, request, jsonify, send_file, Response
from flask_cors import CORS
import torch
import numpy as np
import soundfile as sf

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Flask app initialization
app = Flask(__name__)
CORS(app)

# Global model variable (loaded once at startup)
model = None
tokenizer = None
device = None

# Configuration
MODEL_NAME = os.getenv("VIBEVOICE_MODEL", "microsoft/VibeVoice-1.5B")
USE_QUANTIZATION = os.getenv("USE_QUANTIZATION", "true").lower() == "true"
PORT = int(os.getenv("PORT", "8000"))

def load_model():
    """Load VibeVoice model with optional quantization."""
    global model, tokenizer, device

    logger.info(f"Loading model: {MODEL_NAME}")
    logger.info(f"Quantization enabled: {USE_QUANTIZATION}")

    # Determine device
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    logger.info(f"Using device: {device}")

    try:
        from vibevoice import VibeVoiceModel

        if USE_QUANTIZATION and device.type == "cuda":
            # 4-bit quantization configuration
            from transformers import BitsAndBytesConfig

            quantization_config = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_compute_dtype=torch.bfloat16,
                bnb_4bit_use_double_quant=True,
                bnb_4bit_quant_type="nf4"
            )
            logger.info("Loading model with 4-bit quantization")

            model = VibeVoiceModel.from_pretrained(
                MODEL_NAME,
                quantization_config=quantization_config,
                device_map="auto"
            )
        else:
            logger.info("Loading model without quantization")
            model = VibeVoiceModel.from_pretrained(MODEL_NAME)
            if device.type == "cuda":
                model = model.to(device)

        logger.info("Model loaded successfully")

    except Exception as e:
        logger.error(f"Failed to load model: {str(e)}")
        logger.error(traceback.format_exc())
        raise

def text_to_speech(text: str, speaker_names: list = None, temperature: float = 0.7,
                   cfg_scale: int = 3, inference_steps: int = 50) -> bytes:
    """
    Convert text to speech using VibeVoice.

    Args:
        text: Input text to convert
        speaker_names: List of speaker names for multi-speaker synthesis
        temperature: Sampling temperature
        cfg_scale: Classifier-free guidance scale
        inference_steps: Number of diffusion steps

    Returns:
        WAV audio data as bytes
    """
    try:
        if model is None:
            raise RuntimeError("Model not loaded. Please restart the service.")

        logger.info(f"Processing text of length: {len(text)}")

        # Default speaker names if not provided
        if speaker_names is None:
            speaker_names = ["Speaker0"]

        # Generate speech using VibeVoice model
        audio = model.generate(
            text=text,
            speaker_names=speaker_names,
            temperature=temperature,
            cfg_scale=cfg_scale,
            inference_steps=inference_steps,
            do_sample=True,
            top_p=0.9,
            top_k=50
        )

        # Convert to numpy array if tensor
        if isinstance(audio, torch.Tensor):
            audio = audio.cpu().numpy()

        # Ensure audio is 1D array
        if len(audio.shape) > 1:
            audio = audio.squeeze()

        # Normalize audio to [-1, 1] if needed
        if audio.max() > 1.0 or audio.min() < -1.0:
            audio = audio / np.max(np.abs(audio))

        # Convert to WAV format
        sample_rate = 24000
        buffer = io.BytesIO()
        sf.write(buffer, audio, sample_rate, format='WAV')
        buffer.seek(0)

        logger.info(f"Generated audio of duration: {len(audio) / sample_rate:.2f}s")
        return buffer.read()

    except Exception as e:
        logger.error(f"TTS processing error: {str(e)}")
        logger.error(traceback.format_exc())
        raise

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    return jsonify({
        "status": "healthy",
        "model": MODEL_NAME,
        "device": str(device) if device else "not loaded",
        "quantization": USE_QUANTIZATION
    }), 200

@app.route('/tts', methods=['POST'])
def tts_endpoint():
    """
    Main TTS endpoint.

    Expected JSON body:
    {
        "text": "Text to convert to speech",
        "speaker_names": ["Speaker0"],  # Optional
        "temperature": 0.7,  # Optional
        "cfg_scale": 3,  # Optional
        "inference_steps": 50,  # Optional
        "format": "wav"  # Optional, default is wav
    }
    """
    try:
        # Parse request
        data = request.json
        if not data or 'text' not in data:
            return jsonify({"error": "Missing 'text' field in request"}), 400

        text = data['text']
        speaker_names = data.get('speaker_names', ['Speaker0'])
        temperature = data.get('temperature', 0.7)
        cfg_scale = data.get('cfg_scale', 3)
        inference_steps = data.get('inference_steps', 50)

        logger.info(f"TTS request - Text length: {len(text)}, Speakers: {speaker_names}")

        # Perform TTS
        start_time = time.time()
        audio_data = text_to_speech(text, speaker_names, temperature, cfg_scale, inference_steps)
        processing_time = time.time() - start_time

        logger.info(f"TTS completed in {processing_time:.2f} seconds")

        # Return audio file
        return Response(
            audio_data,
            mimetype='audio/wav',
            headers={
                'Content-Disposition': 'attachment; filename=output.wav',
                'X-Processing-Time': str(processing_time)
            }
        )

    except Exception as e:
        logger.error(f"TTS endpoint error: {str(e)}")
        logger.error(traceback.format_exc())
        return jsonify({"error": str(e)}), 500

@app.route('/batch', methods=['POST'])
def batch_tts_endpoint():
    """
    Batch TTS endpoint for processing multiple texts.

    Expected JSON body:
    {
        "texts": ["Text 1", "Text 2", ...],
        "speaker_names": ["Speaker0"],  # Optional
        "temperature": 0.7  # Optional
    }
    """
    try:
        data = request.json
        if not data or 'texts' not in data:
            return jsonify({"error": "Missing 'texts' field in request"}), 400

        texts = data['texts']
        speaker_names = data.get('speaker_names', ['Speaker0'])
        temperature = data.get('temperature', 0.7)

        logger.info(f"Batch TTS request - {len(texts)} texts")

        results = []
        for i, text in enumerate(texts):
            logger.info(f"Processing text {i+1}/{len(texts)}")
            audio_data = text_to_speech(text, speaker_names, temperature)
            # In a real implementation, you might concatenate or return separately
            results.append(len(audio_data))

        return jsonify({
            "status": "success",
            "processed": len(texts),
            "audio_sizes": results
        }), 200

    except Exception as e:
        logger.error(f"Batch TTS endpoint error: {str(e)}")
        logger.error(traceback.format_exc())
        return jsonify({"error": str(e)}), 500

@app.errorhandler(404)
def not_found(e):
    """404 error handler."""
    return jsonify({"error": "Endpoint not found"}), 404

@app.errorhandler(500)
def internal_error(e):
    """500 error handler."""
    logger.error(f"Internal server error: {str(e)}")
    return jsonify({"error": "Internal server error"}), 500

def main():
    """Main entry point."""
    logger.info("Starting VibeVoice TTS Service")
    logger.info(f"Model: {MODEL_NAME}")
    logger.info(f"Port: {PORT}")

    # Load model on startup
    try:
        load_model()
    except Exception as e:
        logger.error(f"Failed to initialize model: {str(e)}")
        # Continue anyway for development/testing

    # Run Flask app
    app.run(
        host='0.0.0.0',
        port=PORT,
        debug=False  # Set to False in production
    )

if __name__ == '__main__':
    main()