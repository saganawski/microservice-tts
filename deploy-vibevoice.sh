#!/bin/bash

# VibeVoice Deployment Script
# This script helps deploy and configure the VibeVoice TTS service

set -e

echo "VibeVoice TTS Service Deployment"
echo "================================="

# Check if running as development or production
MODE=${1:-development}

if [ "$MODE" == "development" ]; then
    echo "Running in DEVELOPMENT mode (OpenAI provider by default)"

    # Build the Lambda with OpenAI provider
    echo "Building Lambda functions..."
    mvn clean package

    # Deploy CDK stacks (without VibeVoice)
    echo "Deploying CDK stacks..."
    cd cdk
    cdk deploy FileFlowStack ApiStack --require-approval never
    cd ..

    echo "Development deployment complete!"
    echo "TTS Lambda will use OpenAI by default."

elif [ "$MODE" == "vibevoice-local" ]; then
    echo "Running VibeVoice locally for testing..."

    # Build and run VibeVoice service locally
    cd vibevoice-service

    echo "Building Docker image..."
    docker build -t vibevoice:latest .

    echo "Starting VibeVoice service..."
    docker-compose up -d

    echo "Waiting for service to start..."
    sleep 10

    # Test the service
    echo "Testing VibeVoice service..."
    curl -X GET http://localhost:8000/health || echo "Service may still be starting..."

    cd ..

    echo "VibeVoice is running locally at http://localhost:8000"
    echo "To use it with Lambda, set:"
    echo "  TTS_PROVIDER=VIBEVOICE"
    echo "  VIBEVOICE_ENDPOINT_URL=http://your-ip:8000"

elif [ "$MODE" == "vibevoice-deploy" ]; then
    echo "Deploying VibeVoice to AWS EC2..."

    # Build everything
    echo "Building all components..."
    mvn clean package

    # Deploy with VibeVoice stack
    echo "Deploying CDK stacks with VibeVoice..."
    cd cdk
    DEPLOY_VIBEVOICE=true cdk deploy --all --require-approval never
    cd ..

    # Get the VibeVoice service URL from stack outputs
    VIBEVOICE_URL=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceServiceUrl`].OutputValue' \
        --output text)

    echo "VibeVoice deployed successfully!"
    echo "Service URL: $VIBEVOICE_URL"

    # Update Lambda environment to use VibeVoice
    echo "Updating Lambda configuration..."
    aws lambda update-function-configuration \
        --function-name FileFlowStack-TTSLambda \
        --environment Variables="{TTS_PROVIDER=VIBEVOICE,VIBEVOICE_ENDPOINT_URL=$VIBEVOICE_URL}"

    echo "Lambda configured to use VibeVoice!"

elif [ "$MODE" == "switch-to-openai" ]; then
    echo "Switching Lambda back to OpenAI provider..."

    aws lambda update-function-configuration \
        --function-name FileFlowStack-TTSLambda \
        --environment Variables="{TTS_PROVIDER=OPENAI}"

    echo "Lambda switched to OpenAI provider!"

    # Stop the EC2 instance to save costs
    echo "Stopping VibeVoice EC2 instance to save costs..."
    INSTANCE_ID=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceInstanceId`].OutputValue' \
        --output text 2>/dev/null)

    if [ ! -z "$INSTANCE_ID" ]; then
        aws ec2 stop-instances --instance-ids $INSTANCE_ID
        echo "VibeVoice EC2 instance stopped. You're not being charged for compute (only EBS storage ~$8/month)"
    fi

elif [ "$MODE" == "switch-to-vibevoice" ]; then
    echo "Switching Lambda to VibeVoice provider..."

    # Get the VibeVoice service URL
    VIBEVOICE_URL=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceServiceUrl`].OutputValue' \
        --output text)

    if [ -z "$VIBEVOICE_URL" ]; then
        echo "Error: VibeVoice stack not deployed. Run './deploy-vibevoice.sh vibevoice-deploy' first."
        exit 1
    fi

    # Start the EC2 instance if it's stopped
    echo "Starting VibeVoice EC2 instance..."
    INSTANCE_ID=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceInstanceId`].OutputValue' \
        --output text)

    if [ ! -z "$INSTANCE_ID" ]; then
        aws ec2 start-instances --instance-ids $INSTANCE_ID
        echo "Waiting for instance to be running..."
        aws ec2 wait instance-running --instance-ids $INSTANCE_ID
        echo "VibeVoice EC2 instance started!"

        # Wait a bit for the service to be ready
        echo "Waiting 30 seconds for VibeVoice service to be ready..."
        sleep 30
    fi

    aws lambda update-function-configuration \
        --function-name FileFlowStack-TTSLambda \
        --environment Variables="{TTS_PROVIDER=VIBEVOICE,VIBEVOICE_ENDPOINT_URL=$VIBEVOICE_URL}"

    echo "Lambda switched to VibeVoice provider!"

elif [ "$MODE" == "vibevoice-stop" ]; then
    echo "Stopping VibeVoice EC2 instance..."

    INSTANCE_ID=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceInstanceId`].OutputValue' \
        --output text 2>/dev/null)

    if [ ! -z "$INSTANCE_ID" ]; then
        aws ec2 stop-instances --instance-ids $INSTANCE_ID
        echo "VibeVoice EC2 instance stopped."
        echo "💰 You're now only paying for EBS storage (~$8/month), not compute (~$378/month)"
    else
        echo "No VibeVoice instance found."
    fi

elif [ "$MODE" == "vibevoice-status" ]; then
    echo "Checking VibeVoice status..."

    # Check if stack exists
    STACK_STATUS=$(aws cloudformation describe-stacks \
        --stack-name VibeVoiceStack \
        --query 'Stacks[0].StackStatus' \
        --output text 2>/dev/null)

    if [ -z "$STACK_STATUS" ]; then
        echo "❌ VibeVoice stack not deployed"
        echo "   Run './deploy-vibevoice.sh vibevoice-deploy' to deploy"
    else
        echo "✅ VibeVoice stack status: $STACK_STATUS"

        # Get instance details
        INSTANCE_ID=$(aws cloudformation describe-stacks \
            --stack-name VibeVoiceStack \
            --query 'Stacks[0].Outputs[?OutputKey==`VibeVoiceInstanceId`].OutputValue' \
            --output text)

        if [ ! -z "$INSTANCE_ID" ]; then
            INSTANCE_STATE=$(aws ec2 describe-instances \
                --instance-ids $INSTANCE_ID \
                --query 'Reservations[0].Instances[0].State.Name' \
                --output text)

            echo "   Instance ID: $INSTANCE_ID"
            echo "   Instance State: $INSTANCE_STATE"

            if [ "$INSTANCE_STATE" == "running" ]; then
                echo "   💵 COSTING: ~$0.526/hour (g4dn.xlarge)"
            elif [ "$INSTANCE_STATE" == "stopped" ]; then
                echo "   💰 SAVING: Instance stopped, only paying for EBS storage"
            fi
        fi

        # Check Lambda configuration
        TTS_PROVIDER=$(aws lambda get-function-configuration \
            --function-name FileFlowStack-TTSLambda \
            --query 'Environment.Variables.TTS_PROVIDER' \
            --output text 2>/dev/null)

        echo ""
        echo "Lambda Configuration:"
        echo "   TTS_PROVIDER: ${TTS_PROVIDER:-OPENAI}"
    fi

elif [ "$MODE" == "vibevoice-destroy" ]; then
    echo "⚠️  WARNING: This will destroy the VibeVoice stack and all resources!"
    read -p "Are you sure? (yes/no): " confirm

    if [ "$confirm" == "yes" ]; then
        echo "Destroying VibeVoice stack..."
        cd cdk
        cdk destroy VibeVoiceStack --force
        cd ..
        echo "VibeVoice stack destroyed."
    else
        echo "Cancelled."
    fi

else
    echo "Usage: $0 [command]"
    echo ""
    echo "Deployment Commands:"
    echo "  development         - Deploy with OpenAI TTS (default)"
    echo "  vibevoice-local     - Run VibeVoice locally for testing"
    echo "  vibevoice-deploy    - Deploy VibeVoice to AWS EC2"
    echo ""
    echo "Provider Switching:"
    echo "  switch-to-openai    - Switch Lambda to use OpenAI (stops EC2)"
    echo "  switch-to-vibevoice - Switch Lambda to use VibeVoice (starts EC2)"
    echo ""
    echo "Cost Management:"
    echo "  vibevoice-stop      - Stop EC2 instance (save ~$378/month)"
    echo "  vibevoice-status    - Check VibeVoice deployment status"
    echo "  vibevoice-destroy   - Destroy VibeVoice stack completely"
    echo ""
    echo "💡 TIP: Use 'vibevoice-stop' when not using VibeVoice to save money!"
    exit 1
fi