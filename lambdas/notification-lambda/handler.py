#!/usr/bin/env python3
"""
Job Completion Notification Lambda
Receives SNS notifications when audio stitching completes,
generates presigned download URLs, and sends email notifications via SES.
Falls back to SNS if no user email is found.
"""

import json
import boto3
import os
import logging
from datetime import datetime
from typing import Dict, Any, List, Optional
from botocore.config import Config

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3', config=Config(signature_version='s3v4'))
sns_client = boto3.client('sns')
ses_client = boto3.client('ses')
dynamodb = boto3.resource('dynamodb')

# Environment variables
PROCESSED_BUCKET = os.environ.get('PROCESSED_BUCKET_NAME', 'processed-file-bucket272765753210')
NOTIFICATION_TOPIC_ARN = os.environ.get('NOTIFICATION_TOPIC_ARN', '')
PRESIGNED_URL_EXPIRY = int(os.environ.get('PRESIGNED_URL_EXPIRY_HOURS', '72')) * 3600  # Default 72 hours
SES_FROM_EMAIL = os.environ.get('SES_FROM_EMAIL', 'kennethsaganski@gmail.com')
JOB_STATUS_TABLE = os.environ.get('JOB_STATUS_TABLE', 'JobStatus')


def get_user_email(job_id: str) -> Optional[str]:
    """Look up user email from DynamoDB JobStatus table."""
    try:
        table = dynamodb.Table(JOB_STATUS_TABLE)
        response = table.get_item(Key={'job_id': job_id})
        item = response.get('Item', {})
        email = item.get('user_email')
        if email:
            logger.info(f"Found user email for job {job_id}: {email}")
        else:
            logger.info(f"No user email found for job {job_id}")
        return email
    except Exception as e:
        logger.error(f"Error looking up user email for job {job_id}: {str(e)}")
        return None


def generate_presigned_url(bucket: str, key: str, expiry: int = PRESIGNED_URL_EXPIRY) -> str:
    """Generate a presigned URL for S3 object download."""
    try:
        url = s3_client.generate_presigned_url(
            'get_object',
            Params={'Bucket': bucket, 'Key': key},
            ExpiresIn=expiry
        )
        return url
    except Exception as e:
        logger.error(f"Error generating presigned URL for {key}: {str(e)}")
        return None


def get_file_info(bucket: str, key: str) -> Dict[str, Any]:
    """Get file metadata from S3."""
    try:
        response = s3_client.head_object(Bucket=bucket, Key=key)
        size_bytes = response.get('ContentLength', 0)
        size_mb = round(size_bytes / (1024 * 1024), 1)

        # Get custom metadata if available
        metadata = response.get('Metadata', {})

        return {
            'size_bytes': size_bytes,
            'size_mb': size_mb,
            'last_modified': response.get('LastModified'),
            'content_type': response.get('ContentType'),
            'metadata': metadata
        }
    except Exception as e:
        logger.error(f"Error getting file info for {key}: {str(e)}")
        return {}


def format_duration(seconds: float) -> str:
    """Format seconds into human-readable duration."""
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = int(seconds % 60)

    if hours > 0:
        return f"{hours}h {minutes}m {secs}s"
    elif minutes > 0:
        return f"{minutes}m {secs}s"
    else:
        return f"{secs}s"


def calculate_audio_duration(size_bytes: int) -> float:
    """Estimate audio duration from file size (24kHz, 16-bit, mono WAV)."""
    # WAV at 24kHz, 16-bit, mono = 48000 bytes per second
    bytes_per_second = 48000
    return (size_bytes - 44) / bytes_per_second  # Subtract WAV header


def build_notification_message(job_id: str, parts: List[Dict]) -> Dict[str, str]:
    """Build the notification message with download links."""

    total_size_mb = sum(p.get('size_mb', 0) for p in parts)
    total_duration_seconds = sum(
        calculate_audio_duration(p.get('size_bytes', 0)) for p in parts
    )

    # Build subject
    subject = f"TTS Complete: {job_id}"

    # Build message body
    lines = [
        f"Your audiobook is ready!",
        f"",
        f"Job: {job_id}",
        f"Parts: {len(parts)}",
        f"Total Size: {total_size_mb:.1f} MB",
        f"Total Duration: {format_duration(total_duration_seconds)}",
        f"",
        f"Download Links (valid for {PRESIGNED_URL_EXPIRY // 3600} hours):",
        f"",
    ]

    for i, part in enumerate(parts, 1):
        duration = format_duration(calculate_audio_duration(part.get('size_bytes', 0)))
        lines.append(f"Part {i}: {part.get('size_mb', 0):.1f} MB ({duration})")
        lines.append(f"{part.get('download_url', 'URL unavailable')}")
        lines.append("")

    lines.extend([
        f"---",
        f"Generated at {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')} UTC",
        f"Files stored in: s3://{PROCESSED_BUCKET}/jobs/{job_id}/",
    ])

    return {
        'subject': subject,
        'message': '\n'.join(lines)
    }


def list_job_parts(job_id: str) -> List[Dict]:
    """List all audio parts for a job from S3."""
    parts = []
    prefix = f"jobs/{job_id}/"

    try:
        response = s3_client.list_objects_v2(
            Bucket=PROCESSED_BUCKET,
            Prefix=prefix
        )

        for obj in response.get('Contents', []):
            key = obj['Key']
            if key.endswith('.wav'):
                file_info = get_file_info(PROCESSED_BUCKET, key)
                download_url = generate_presigned_url(PROCESSED_BUCKET, key)

                parts.append({
                    'key': key,
                    'filename': key.split('/')[-1],
                    'size_bytes': file_info.get('size_bytes', 0),
                    'size_mb': file_info.get('size_mb', 0),
                    'download_url': download_url,
                    'metadata': file_info.get('metadata', {})
                })

        # Sort by part number
        parts.sort(key=lambda x: x['filename'])

    except Exception as e:
        logger.error(f"Error listing job parts: {str(e)}")

    return parts


def send_ses_email(to_email: str, subject: str, message: str) -> bool:
    """Send email directly via SES."""
    try:
        response = ses_client.send_email(
            Source=SES_FROM_EMAIL,
            Destination={'ToAddresses': [to_email]},
            Message={
                'Subject': {'Data': subject[:100], 'Charset': 'UTF-8'},
                'Body': {'Text': {'Data': message, 'Charset': 'UTF-8'}}
            }
        )
        logger.info(f"SES email sent to {to_email}: {response.get('MessageId')}")
        return True
    except Exception as e:
        logger.error(f"Error sending SES email to {to_email}: {str(e)}")
        return False


def send_sns_notification(subject: str, message: str) -> bool:
    """Send notification via SNS (fallback)."""
    if not NOTIFICATION_TOPIC_ARN:
        logger.warning("No notification topic ARN configured")
        return False

    try:
        response = sns_client.publish(
            TopicArn=NOTIFICATION_TOPIC_ARN,
            Subject=subject[:100],  # SNS subject limit
            Message=message
        )
        logger.info(f"SNS notification sent: {response.get('MessageId')}")
        return True
    except Exception as e:
        logger.error(f"Error sending SNS notification: {str(e)}")
        return False


def send_notification(job_id: str, subject: str, message: str) -> bool:
    """Send notification to user via SES if email found, otherwise fall back to SNS."""
    user_email = get_user_email(job_id)

    if user_email:
        success = send_ses_email(user_email, subject, message)
        if success:
            return True
        logger.warning(f"SES failed for {user_email}, falling back to SNS")

    return send_sns_notification(subject, message)


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Main handler for job completion notifications.

    Triggered by SNS when AudioStitchingLambda completes.
    Expected SNS message format:
    {
        "job_id": "book-name-timestamp",
        "status": "completed",
        "total_parts": 3,
        "parts": [
            {"part_number": 1, "s3_key": "jobs/xxx/part_01.wav", "size": 123456},
            ...
        ]
    }
    """
    logger.info(f"Received event: {json.dumps(event)}")

    results = []

    try:
        # Handle SNS event
        if 'Records' in event:
            for record in event['Records']:
                if record.get('EventSource') == 'aws:sns':
                    raw_message = record['Sns']['Message']
                    try:
                        sns_message = json.loads(raw_message)
                    except (json.JSONDecodeError, TypeError):
                        logger.info("Skipping non-JSON SNS message (likely a forwarded notification)")
                        continue
                    job_id = sns_message.get('job_id')

                    if not job_id:
                        logger.error("No job_id in SNS message")
                        continue

                    logger.info(f"Processing completion notification for job: {job_id}")

                    # Get all parts with download URLs
                    parts = list_job_parts(job_id)

                    if not parts:
                        logger.error(f"No audio parts found for job {job_id}")
                        results.append({
                            'job_id': job_id,
                            'status': 'error',
                            'error': 'No audio parts found'
                        })
                        continue

                    # Build and send notification
                    notification = build_notification_message(job_id, parts)
                    success = send_notification(
                        job_id,
                        notification['subject'],
                        notification['message']
                    )

                    results.append({
                        'job_id': job_id,
                        'status': 'notified' if success else 'notification_failed',
                        'parts_count': len(parts),
                        'total_size_mb': sum(p.get('size_mb', 0) for p in parts)
                    })

        # Handle direct invocation (for testing)
        elif 'job_id' in event:
            job_id = event['job_id']
            logger.info(f"Direct invocation for job: {job_id}")

            parts = list_job_parts(job_id)

            if not parts:
                return {
                    'statusCode': 404,
                    'body': json.dumps({'error': f'No audio parts found for job {job_id}'})
                }

            notification = build_notification_message(job_id, parts)

            # Optionally send notification
            if event.get('send_notification', True):
                send_notification(job_id, notification['subject'], notification['message'])

            return {
                'statusCode': 200,
                'body': json.dumps({
                    'job_id': job_id,
                    'parts': parts,
                    'notification': notification
                }, default=str)
            }

        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': f'Processed {len(results)} notifications',
                'results': results
            })
        }

    except Exception as e:
        logger.error(f"Error in notification handler: {str(e)}", exc_info=True)
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }
