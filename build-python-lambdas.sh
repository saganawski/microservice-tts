#!/bin/bash
# Master build script for all Python lambdas
# Run this before CDK deployment to ensure all dependencies are packaged

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================="
echo "Building all Python Lambda functions..."
echo "========================================="

# List of Python lambda directories
PYTHON_LAMBDAS=(
    "lambdas/textract-extraction-lambda"
    "lambdas/text-chunking-lambda"
    "lambdas/audio-stitching-lambda"
    "lambdas/tts-generation-lambda"
    "lambdas/notification-lambda"
    "lambdas/audit-lambda"
)

# Build each Python lambda
for lambda_dir in "${PYTHON_LAMBDAS[@]}"; do
    FULL_PATH="$SCRIPT_DIR/$lambda_dir"

    if [ -d "$FULL_PATH" ]; then
        echo ""
        echo "Processing: $lambda_dir"
        echo "-----------------------------------------"

        # Check if it's a Python lambda (has handler.py or requirements.txt)
        if [ -f "$FULL_PATH/handler.py" ] || [ -f "$FULL_PATH/requirements.txt" ]; then
            # Check if package.sh exists
            if [ -f "$FULL_PATH/package.sh" ]; then
                echo "Running package.sh for $lambda_dir..."
                cd "$FULL_PATH"
                chmod +x package.sh
                ./package.sh
            elif [ -f "$FULL_PATH/requirements.txt" ]; then
                echo "Installing dependencies from requirements.txt..."
                cd "$FULL_PATH"
                pip install -r requirements.txt -t . --upgrade

                # Clean up
                find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
                find . -name "*.pyc" -delete 2>/dev/null || true
                rm -rf *.dist-info 2>/dev/null || true
                rm -f *.egg-info 2>/dev/null || true
            else
                echo "No requirements.txt found, skipping dependency installation"
            fi
        else
            echo "Not a Python lambda, skipping..."
        fi
    else
        echo "Warning: Directory not found: $lambda_dir"
    fi
done

echo ""
echo "========================================="
echo "Python Lambda build complete!"
echo "========================================="
echo ""
echo "You can now deploy with CDK:"
echo "  mvn clean package  # Build Java lambdas"
echo "  cdk deploy --all   # Deploy all stacks"