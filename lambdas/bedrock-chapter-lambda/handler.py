#!/usr/bin/env python3
"""
Bedrock Chapter Detection Lambda Handler
Uses Amazon Bedrock (Claude) to intelligently detect chapters and split books
Uploads EACH chapter as individual file to trigger parallel TTS processing
"""

import json
import boto3
import os
from typing import Dict, Any, List
import logging
import re
from prompts import get_chapter_detection_prompt

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
bedrock_client = boto3.client('bedrock-runtime')

# Environment variables
MARKDOWN_BUCKET_NAME = os.environ.get('MARKDOWN_BUCKET_NAME')
CHAPTERS_BUCKET_NAME = os.environ.get('CHAPTERS_BUCKET_NAME')
BEDROCK_MODEL_ID = os.environ.get('BEDROCK_MODEL_ID', 'anthropic.claude-sonnet-4-5-20250929-v1:0')
BEDROCK_REGION = os.environ.get('BEDROCK_REGION', 'us-east-1')


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for intelligent chapter detection and splitting
    Triggered by S3 events from MarkdownFileBucket

    S3 Event format:
    {
        "Records": [{
            "s3": {
                "bucket": {"name": "markdown-file-bucket..."},
                "object": {"key": "dracula.md"}
            }
        }]
    }
    """
    try:
        # Extract S3 event details
        record = event['Records'][0]
        bucket_name = record['s3']['bucket']['name']
        object_key = record['s3']['object']['key']

        logger.info(f"Processing markdown file - Bucket: {bucket_name}, Key: {object_key}")

        # Download markdown content
        markdown_content = download_markdown_from_s3(bucket_name, object_key)

        if not markdown_content:
            logger.error("Failed to download markdown or empty content")
            return {
                'statusCode': 500,
                'body': json.dumps({'error': 'Failed to download markdown'})
            }

        logger.info(f"Downloaded {len(markdown_content)} characters from {object_key}")

        # Use Bedrock Claude to detect chapters
        chapters = detect_chapters_with_bedrock(markdown_content)

        if not chapters:
            logger.error("No chapters detected")
            return {
                'statusCode': 500,
                'body': json.dumps({'error': 'No chapters detected'})
            }

        logger.info(f"Detected {len(chapters)} chapters")

        # Generate base name for chapter files
        base_name = object_key.replace('.md', '')

        # Upload EACH chapter as individual file (triggers TTS lambda for each)
        uploaded_count = upload_chapters_individually(base_name, chapters)

        # Create and upload metadata
        upload_metadata(base_name, chapters)

        logger.info(f"Successfully uploaded {uploaded_count}/{len(chapters)} chapters")

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': 'Chapters detected and uploaded successfully',
                'totalChapters': len(chapters),
                'uploadedChapters': uploaded_count,
                'baseName': base_name
            })
        }

    except Exception as e:
        logger.error(f"Error processing chapter detection: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }


def download_markdown_from_s3(bucket_name: str, object_key: str) -> str:
    """
    Download markdown file from S3

    Args:
        bucket_name: S3 bucket name
        object_key: S3 object key

    Returns:
        Markdown content as string
    """
    try:
        response = s3_client.get_object(Bucket=bucket_name, Key=object_key)
        content = response['Body'].read().decode('utf-8')
        return content
    except Exception as e:
        logger.error(f"Error downloading markdown: {str(e)}", exc_info=True)
        return ""


def detect_chapters_with_bedrock(markdown_content: str) -> List[Dict]:
    """
    Use Bedrock Claude to intelligently detect chapters

    Args:
        markdown_content: Full book markdown

    Returns:
        List of chapter dictionaries with index, title, content
    """
    logger.info("Invoking Bedrock Claude for chapter detection")

    try:
        # Generate prompt
        prompt = get_chapter_detection_prompt(markdown_content)

        # Prepare Bedrock request
        request_body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": 100000,  # Large enough for chapter extraction
            "temperature": 0.1,  # Low temperature for consistent parsing
            "messages": [
                {
                    "role": "user",
                    "content": prompt
                }
            ]
        }

        # Invoke Bedrock
        response = bedrock_client.invoke_model(
            modelId=BEDROCK_MODEL_ID,
            body=json.dumps(request_body)
        )

        # Parse response
        response_body = json.loads(response['body'].read())
        logger.info(f"Bedrock response received. Stop reason: {response_body.get('stop_reason')}")

        # Extract content from Claude response
        content = response_body['content'][0]['text']

        # Log token usage
        usage = response_body.get('usage', {})
        logger.info(
            f"Token usage - Input: {usage.get('input_tokens')}, "
            f"Output: {usage.get('output_tokens')}"
        )

        # Parse JSON response
        chapters = parse_claude_response(content)

        return chapters

    except Exception as e:
        logger.error(f"Error invoking Bedrock: {str(e)}", exc_info=True)
        return []


def parse_claude_response(response_text: str) -> List[Dict]:
    """
    Parse Claude's JSON response to extract chapters

    Args:
        response_text: Claude's text response

    Returns:
        List of chapter dictionaries
    """
    try:
        # Try to find JSON in response (Claude might add extra text)
        json_match = re.search(r'\{.*\}', response_text, re.DOTALL)
        if json_match:
            json_str = json_match.group(0)
            data = json.loads(json_str)
        else:
            data = json.loads(response_text)

        chapters = data.get('chapters', [])
        logger.info(f"Parsed {len(chapters)} chapters from Claude response")

        # Validate chapters
        for chapter in chapters:
            if 'index' not in chapter or 'content' not in chapter:
                logger.warning(f"Invalid chapter format: {chapter.get('title', 'Unknown')}")
                continue

        return chapters

    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse JSON from Claude response: {str(e)}")
        logger.error(f"Response text (first 500 chars): {response_text[:500]}")
        return []


def upload_chapters_individually(base_name: str, chapters: List[Dict]) -> int:
    """
    Upload EACH chapter as individual S3 object to trigger parallel TTS processing

    Args:
        base_name: Base name for chapter files (e.g., "dracula")
        chapters: List of chapter dictionaries

    Returns:
        Number of successfully uploaded chapters
    """
    uploaded_count = 0

    for chapter in chapters:
        try:
            index = chapter['index']
            title = chapter.get('title', f'Chapter {index}')
            content = chapter['content']

            # Generate padded filename: 001.md, 002.md, etc.
            chapter_filename = f"{str(index).zfill(3)}.md"
            chapter_key = f"{base_name}/{chapter_filename}"

            logger.info(f"Uploading chapter {index}: {title} ({len(content)} chars)")

            # Upload to ChaptersBucket
            # This triggers GeminiTTS Lambda for THIS chapter only!
            s3_client.put_object(
                Bucket=CHAPTERS_BUCKET_NAME,
                Key=chapter_key,
                Body=content,
                ContentType='text/markdown',
                Metadata={
                    'chapter-index': str(index),
                    'chapter-title': title,
                    'word-count': str(count_words(content))
                }
            )

            uploaded_count += 1
            logger.info(f"✓ Uploaded: {chapter_key}")

        except Exception as e:
            logger.error(f"Failed to upload chapter {chapter.get('index')}: {str(e)}")
            continue

    return uploaded_count


def upload_metadata(base_name: str, chapters: List[Dict]) -> bool:
    """
    Upload metadata JSON with chapter index

    Args:
        base_name: Base name for files
        chapters: List of chapter dictionaries

    Returns:
        True if successful
    """
    try:
        metadata = {
            'sourceFile': f"{base_name}.md",
            'totalChapters': len(chapters),
            'processedAt': os.environ.get('AWS_EXECUTION_ENV', 'local'),
            'chapters': [
                {
                    'index': ch['index'],
                    'title': ch.get('title', f'Chapter {ch["index"]}'),
                    'fileName': f"{str(ch['index']).zfill(3)}.md",
                    'wordCount': count_words(ch['content']),
                    'status': 'pending'
                }
                for ch in chapters
            ]
        }

        metadata_key = f"{base_name}/metadata.json"

        s3_client.put_object(
            Bucket=CHAPTERS_BUCKET_NAME,
            Key=metadata_key,
            Body=json.dumps(metadata, indent=2),
            ContentType='application/json'
        )

        logger.info(f"Uploaded metadata: {metadata_key}")
        return True

    except Exception as e:
        logger.error(f"Failed to upload metadata: {str(e)}")
        return False


def count_words(text: str) -> int:
    """Count words in text"""
    return len(text.split())


# For local testing
if __name__ == "__main__":
    # Test event
    test_event = {
        "Records": [{
            "s3": {
                "bucket": {"name": "markdown-file-bucket272765753210"},
                "object": {"key": "test-book.md"}
            }
        }]
    }

    # Mock environment variables
    os.environ['MARKDOWN_BUCKET_NAME'] = 'markdown-file-bucket272765753210'
    os.environ['CHAPTERS_BUCKET_NAME'] = 'chapters-bucket272765753210'
    os.environ['BEDROCK_MODEL_ID'] = 'anthropic.claude-sonnet-4-5-20250929-v1:0'

    result = lambda_handler(test_event, None)
    print(json.dumps(json.loads(result['body']), indent=2))
