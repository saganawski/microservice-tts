#!/bin/bash
# Package notification-lambda for deployment

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Packaging notification-lambda..."

# Clean previous build
rm -rf package/ lambda.zip

# Create package directory
mkdir -p package

# Install dependencies
pip install -r requirements.txt -t package/ --quiet

# Copy handler
cp handler.py package/

# Create zip
cd package
zip -r ../lambda.zip . -q

cd ..
echo "Created lambda.zip ($(du -h lambda.zip | cut -f1))"
