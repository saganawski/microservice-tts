#!/bin/bash
# Package audit-lambda for AWS Lambda deployment

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "📦 Packaging audit-lambda..."

# Clean previous build
rm -rf package/ lambda.zip

# Create package directory
mkdir -p package

# Install dependencies
echo "Installing dependencies..."
pip install -r requirements.txt -t package/ --quiet

# Copy handler
cp handler.py package/

# Create zip
cd package
zip -r ../lambda.zip . -x "*.pyc" -x "__pycache__/*" -x "*.dist-info/*" --quiet

cd ..
echo "✅ Created lambda.zip ($(du -h lambda.zip | cut -f1))"

# Cleanup
rm -rf package/

echo "Done! Upload lambda.zip to AWS Lambda"
