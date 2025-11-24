#!/bin/bash
# Build script for tts-generation-lambda
# This ensures all dependencies are installed locally for CDK deployment

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building tts-generation-lambda..."

# Check if requirements.txt exists
if [ ! -f requirements.txt ]; then
    echo "Error: requirements.txt not found!"
    exit 1
fi

# Install dependencies to the current directory
echo "Installing Python dependencies..."
pip install -r requirements.txt -t . --upgrade

# Clean up any __pycache__ directories
find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find . -name "*.pyc" -delete 2>/dev/null || true

# Remove unnecessary files
rm -rf *.dist-info 2>/dev/null || true
rm -f *.egg-info 2>/dev/null || true

echo "TTS-generation-lambda build complete!"
echo "Dependencies installed to: $SCRIPT_DIR"