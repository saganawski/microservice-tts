#!/usr/bin/env python3
"""
Textract Processor Lambda Handler
Retrieves completed Textract results and creates consolidated markdown
"""

import json
import boto3
import os
from typing import Dict, Any, List
import logging

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
textract_client = boto3.client('textract')

# Environment variables
MARKDOWN_BUCKET_NAME = os.environ.get('MARKDOWN_BUCKET_NAME')


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for processing Textract completion notifications
    Triggered by SNS notifications from Textract

    SNS Event format:
    {
        "Records": [{
            "Sns": {
                "Message": "{\"JobId\": \"...\", \"Status\": \"SUCCEEDED\", ...}"
            }
        }]
    }
    """
    try:
        # Extract SNS message
        sns_message = json.loads(event['Records'][0]['Sns']['Message'])

        job_id = sns_message['JobId']
        status = sns_message['Status']

        logger.info(f"Received Textract completion notification - Job ID: {job_id}, Status: {status}")

        if status != 'SUCCEEDED':
            logger.error(f"Textract job failed with status: {status}")
            return {
                'statusCode': 500,
                'body': json.dumps({'error': f'Textract job failed: {status}'})
            }

        # Retrieve Textract results
        markdown_content = retrieve_textract_results(job_id)

        if not markdown_content:
            logger.error("Failed to retrieve Textract results or empty content")
            return {
                'statusCode': 500,
                'body': json.dumps({'error': 'Failed to retrieve Textract results'})
            }

        # Extract document metadata from SNS message
        document_location = sns_message.get('DocumentLocation', {})
        source_bucket = document_location.get('S3ObjectName', '').split('/')[0] if document_location else ''
        source_key = sns_message.get('JobTag', 'unknown-document')  # We stored filename in JobTag

        # Generate markdown filename
        markdown_filename = generate_markdown_filename(source_key)

        # Upload to MarkdownFileBucket
        upload_success = upload_markdown_to_s3(markdown_content, markdown_filename)

        if not upload_success:
            logger.error("Failed to upload markdown to S3")
            return {
                'statusCode': 500,
                'body': json.dumps({'error': 'Failed to upload markdown'})
            }

        logger.info(f"Successfully processed Textract job {job_id}")
        logger.info(f"Created markdown file: {markdown_filename}")

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': 'Textract results processed successfully',
                'jobId': job_id,
                'markdownFile': markdown_filename,
                'characterCount': len(markdown_content)
            })
        }

    except Exception as e:
        logger.error(f"Error processing Textract results: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }


def retrieve_textract_results(job_id: str) -> str:
    """
    Retrieve all pages from Textract job and consolidate into markdown

    Args:
        job_id: Textract job ID

    Returns:
        Consolidated markdown content
    """
    logger.info(f"Retrieving Textract results for job: {job_id}")

    markdown_content = []
    next_token = None
    page_count = 0

    try:
        # Paginate through all results
        while True:
            if next_token:
                response = textract_client.get_document_text_detection(
                    JobId=job_id,
                    NextToken=next_token
                )
            else:
                response = textract_client.get_document_text_detection(JobId=job_id)

            # Extract text blocks
            blocks = response.get('Blocks', [])

            # Process blocks and extract text with layout
            page_text = extract_text_from_blocks(blocks)
            if page_text:
                markdown_content.append(page_text)
                page_count += 1

            # Check for more pages
            next_token = response.get('NextToken')
            if not next_token:
                break

        logger.info(f"Retrieved {page_count} pages from Textract job {job_id}")

        # Consolidate all pages
        consolidated = '\n\n'.join(markdown_content)
        logger.info(f"Consolidated markdown: {len(consolidated)} characters")

        return consolidated

    except Exception as e:
        logger.error(f"Error retrieving Textract results: {str(e)}", exc_info=True)
        return ""


def extract_text_from_blocks(blocks: List[Dict]) -> str:
    """
    Extract text from Textract blocks with layout preservation

    Args:
        blocks: List of Textract block objects

    Returns:
        Extracted text with basic markdown formatting
    """
    lines = []
    current_page = None

    for block in blocks:
        block_type = block.get('BlockType')

        if block_type == 'PAGE':
            current_page = block.get('Page', 1)

        elif block_type == 'LINE':
            text = block.get('Text', '').strip()
            if text:
                # Detect potential headers (all caps, short lines)
                if len(text) < 100 and text.isupper() and len(text.split()) < 10:
                    lines.append(f"\n# {text}\n")
                else:
                    lines.append(text)

    return '\n'.join(lines)


def upload_markdown_to_s3(markdown_content: str, filename: str) -> bool:
    """
    Upload consolidated markdown to MarkdownFileBucket

    Args:
        markdown_content: Markdown text content
        filename: Destination filename

    Returns:
        True if successful, False otherwise
    """
    try:
        logger.info(f"Uploading markdown to S3: {filename}")

        s3_client.put_object(
            Bucket=MARKDOWN_BUCKET_NAME,
            Key=filename,
            Body=markdown_content,
            ContentType='text/markdown'
        )

        logger.info(f"Successfully uploaded {len(markdown_content)} characters to {filename}")
        return True

    except Exception as e:
        logger.error(f"Error uploading markdown to S3: {str(e)}", exc_info=True)
        return False


def generate_markdown_filename(source_filename: str) -> str:
    """
    Generate markdown filename from source filename

    Args:
        source_filename: Original file name

    Returns:
        Markdown filename (.md extension)
    """
    # Remove extension and add .md
    base_name = source_filename.rsplit('.', 1)[0] if '.' in source_filename else source_filename
    return f"{base_name}.md"


# For local testing
if __name__ == "__main__":
    # Test event (SNS notification format)
    test_event = {
        "Records": [{
            "Sns": {
                "Message": json.dumps({
                    "JobId": "test-job-12345",
                    "Status": "SUCCEEDED",
                    "JobTag": "test-book.pdf",
                    "DocumentLocation": {
                        "S3ObjectName": "original-file-bucket272765753210/test-book.pdf"
                    }
                })
            }
        }]
    }

    # Mock environment variables
    os.environ['MARKDOWN_BUCKET_NAME'] = 'markdown-file-bucket272765753210'

    result = lambda_handler(test_event, None)
    print(json.dumps(json.loads(result['body']), indent=2))
