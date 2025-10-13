#!/bin/bash
# AWS Profile Switcher
# Usage: source switch_profile.sh {personal|work|bedrock|default|show}

case "$1" in
  personal)
    export AWS_PROFILE=personal
    echo "✅ Switched to personal profile"
    ;;
  work)
    export AWS_PROFILE=work
    echo "✅ Switched to work profile"
    ;;
  bedrock)
    export AWS_PROFILE=bedrock-dev
    echo "✅ Switched to bedrock-dev profile"
    ;;
  default)
    unset AWS_PROFILE
    echo "✅ Switched to default profile"
    ;;
  show)
    if [ -z "$AWS_PROFILE" ]; then
      echo "📍 Current profile: default"
    else
      echo "📍 Current profile: $AWS_PROFILE"
    fi
    echo ""
    aws sts get-caller-identity 2>/dev/null || echo "❌ No valid credentials"
    ;;
  *)
    echo "Usage: source switch_profile.sh {personal|work|bedrock|default|show}"
    echo ""
    echo "Available profiles:"
    echo "  personal   - Personal AWS account"
    echo "  work       - Work AWS account"
    echo "  bedrock    - Bedrock development account"
    echo "  default    - Default AWS profile"
    echo "  show       - Show current active profile"
    ;;
esac
