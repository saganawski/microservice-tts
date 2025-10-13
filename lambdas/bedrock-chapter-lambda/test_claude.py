#!/usr/bin/env python3
"""
Test script for Claude Sonnet 4.5 via AWS Bedrock
"""

import boto3
import json

# Initialize Bedrock client
bedrock = boto3.client('bedrock-runtime', region_name='us-east-1')

def ask_claude(question: str, max_tokens: int = 500):
    """
    Send a question to Claude Sonnet 4.5

    Args:
        question: The question to ask
        max_tokens: Maximum tokens in response
    """
    # Prepare request
    request_body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": max_tokens,
        "messages": [
            {
                "role": "user",
                "content": question
            }
        ]
    }

    print(f"\n📤 Sending to Claude Sonnet 4.5...")
    print(f"Question: {question}\n")

    try:
        # Invoke model
        response = bedrock.invoke_model(
            modelId="us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            body=json.dumps(request_body)
        )

        # Parse response
        response_body = json.loads(response['body'].read())

        # Extract answer
        answer = response_body['content'][0]['text']
        usage = response_body['usage']

        print(f"📥 Response from Claude:")
        print(f"{answer}\n")
        print(f"📊 Token Usage:")
        print(f"  Input: {usage['input_tokens']} tokens")
        print(f"  Output: {usage['output_tokens']} tokens")
        print(f"  Model: {response_body['model']}")

        return answer

    except Exception as e:
        print(f"❌ Error: {e}")
        return None


if __name__ == "__main__":
    # Test with simple question
    ask_claude("What are the main themes in Bram Stoker's Dracula?")

    # Test with token counting
    print("\n" + "="*60)
    ask_claude("Count from 1 to 10", max_tokens=50)

    # Test with longer text analysis
    print("\n" + "="*60)
    sample_text = """
    Jonathan Harker travels to Transylvania to meet Count Dracula.
    The Count is a vampire who moves to England to spread the curse.
    """
    ask_claude(f"Summarize this in one sentence: {sample_text}", max_tokens=100)
