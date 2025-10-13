#!/usr/bin/env python3
"""
Textract Extractor Lambda Handler
Initiates async AWS Textract jobs for PDF/EPUB text extraction
"""

import json
import boto3
import os
from typing import Dict, Any
import logging

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
textract_client = boto3.client('textract')

# Environment variables
ORIGINAL_BUCKET_NAME = os.environ.get('ORIGINAL_BUCKET_NAME')
SNS_TOPIC_ARN = os.environ.get('SNS_TOPIC_ARN')
TEXTRACT_ROLE_ARN = os.environ.get('TEXTRACT_ROLE_ARN')


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for initiating Textract extraction jobs
    Triggered by S3 events from OriginalFileBucket

    S3 Event format:
    {
        "Records": [{
            "s3": {
                "bucket": {"name": "original-file-bucket..."},
                "object": {"key": "book.pdf"}
            }
        }]
    }
    """
    try:
        # Extract S3 event details
        record = event['Records'][0]
        bucket_name = record['s3']['bucket']['name']
        object_key = record['s3']['object']['key']

        logger.info(f"Processing S3 event - Bucket: {bucket_name}, Key: {object_key}")

        # Determine file type
        file_extension = object_key.lower().split('.')[-1]

        if file_extension == 'pdf':
            result = process_pdf_with_textract(bucket_name, object_key)
        elif file_extension in ['epub', 'txt']:
            # For EPUB/TXT, we'll use PyMuPDF or direct text extraction
            # For now, start with Textract for consistency (can optimize later)
            result = process_pdf_with_textract(bucket_name, object_key)
        else:
            logger.error(f"Unsupported file type: {file_extension}")
            return {
                'statusCode': 400,
                'body': json.dumps({'error': f'Unsupported file type: {file_extension}'})
            }

        return result

    except Exception as e:
        logger.error(f"Error processing Textract extraction request: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }


def process_pdf_with_textract(bucket_name: str, object_key: str) -> Dict[str, Any]:
    """
    Start async Textract job for PDF processing

    Args:
        bucket_name: S3 bucket containing the PDF
        object_key: S3 key of the PDF file

    Returns:
        Response dict with job ID and status
    """
    logger.info(f"Starting Textract job for: s3://{bucket_name}/{object_key}")

    # Configure SNS notification for job completion
    notification_channel = {
        'SNSTopicArn': SNS_TOPIC_ARN,
        'RoleArn': TEXTRACT_ROLE_ARN
    }

    # Start async Textract job with LAYOUT feature for document hierarchy
    response = textract_client.start_document_text_detection(
        DocumentLocation={
            'S3Object': {
                'Bucket': bucket_name,
                'Name': object_key
            }
        },
        NotificationChannel=notification_channel,
        JobTag=object_key  # Tag for tracking
    )

    job_id = response['JobId']
    logger.info(f"Textract job started successfully. Job ID: {job_id}")
    logger.info(f"SNS notification will be sent to: {SNS_TOPIC_ARN}")

    # Store job metadata in S3 for tracking (optional)
    metadata = {
        'jobId': job_id,
        'sourceFile': object_key,
        'sourceBucket': bucket_name,
        'status': 'IN_PROGRESS',
        'notificationChannel': SNS_TOPIC_ARN
    }

    metadata_key = f"textract-jobs/{job_id}.json"
    s3_client.put_object(
        Bucket=bucket_name,
        Key=metadata_key,
        Body=json.dumps(metadata),
        ContentType='application/json'
    )

    return {
        'statusCode': 200,
        'body': json.dumps({
            'message': 'Textract job started successfully',
            'jobId': job_id,
            'sourceFile': object_key,
            'notificationChannel': SNS_TOPIC_ARN
        })
    }


# For local testing
if __name__ == "__main__":
    # Test event
    test_event = {
        "Records": [{
            "s3": {
                "bucket": {"name": "original-file-bucket272765753210"},
                "object": {"key": "test-book.pdf"}
            }
        }]
    }

    # Mock environment variables
    os.environ['ORIGINAL_BUCKET_NAME'] = 'original-file-bucket272765753210'
    os.environ['SNS_TOPIC_ARN'] = 'arn:aws:sns:us-east-1:272765753210:textract-completion'
    os.environ['TEXTRACT_ROLE_ARN'] = 'arn:aws:iam::272765753210:role/TextractServiceRole'

    result = lambda_handler(test_event, None)
    print(json.dumps(json.loads(result['body']), indent=2))
