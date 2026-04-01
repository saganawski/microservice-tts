"""
Emotion Preprocessing Lambda

Reads full extracted text from S3, applies Orpheus emotion tags using Gemini Flash,
saves tagged version, and forwards to chunking queue.

Trigger: SQS message from emotion-queue (sent by TextractExtractionLambda):
{
    "job_id": "book-123",
    "text_s3_bucket": "textract-results-bucket...",
    "text_s3_key": "book-123/full_text.txt",
    "total_pages": 42,
    "total_characters": 100000,
    "source_file": "uploads/book.pdf"
}

Output: Saves tagged_full_text.txt to textract-results bucket, then sends
SQS message to chunking-queue with the tagged text S3 location.

Environment variables:
    GEMINI_SECRET_NAME  — Secrets Manager secret name for Gemini API key
    GEMINI_API_KEY      — Gemini API key (fallback if no secret)
    EMOTION_LEVEL       — none | subtle | dramatic (default: subtle)
    TEXTRACT_RESULTS_BUCKET — S3 bucket for textract results (read/write)
    CHUNKING_QUEUE_URL  — SQS FIFO queue URL for text chunking
"""

import json
import logging
import os

import boto3

from emotion_tagger import EmotionTagger

logger = logging.getLogger()
logger.setLevel(logging.INFO)

s3_client = boto3.client("s3")
sqs_client = boto3.client("sqs")

# --- Resolve API key ---
GEMINI_SECRET_NAME = os.environ.get("GEMINI_SECRET_NAME", "")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")

if GEMINI_SECRET_NAME and not GEMINI_API_KEY:
    secrets_client = boto3.client("secretsmanager")
    _resp = secrets_client.get_secret_value(SecretId=GEMINI_SECRET_NAME)
    GEMINI_API_KEY = json.loads(_resp["SecretString"])["api_key"]

# --- Config ---
EMOTION_LEVEL = os.environ.get("EMOTION_LEVEL", "subtle")
TEXTRACT_RESULTS_BUCKET = os.environ.get("TEXTRACT_RESULTS_BUCKET", "")
CHUNKING_QUEUE_URL = os.environ.get("CHUNKING_QUEUE_URL", "")

# Initialize tagger at cold start
tagger = EmotionTagger(api_key=GEMINI_API_KEY, level=EMOTION_LEVEL)


def lambda_handler(event, context):
    """
    Process full extracted text and add emotion tags.
    Triggered by SQS message from emotion-queue.
    """
    records = event.get("Records", [])
    if records:
        messages = [json.loads(r["body"]) for r in records]
    else:
        messages = [event]

    results = []

    for message in messages:
        try:
            result = _process_full_text(message)
            results.append(result)
        except Exception as e:
            logger.error(f"Error processing full text: {e}", exc_info=True)
            results.append({"error": str(e), "message": message})

    return {
        "statusCode": 200,
        "body": json.dumps({"processed": len(results), "results": results}),
    }


def _process_full_text(message: dict) -> dict:
    """Tag full extracted text and forward to chunking queue."""
    job_id = message["job_id"]
    text_bucket = message.get("text_s3_bucket", TEXTRACT_RESULTS_BUCKET)
    text_key = message["text_s3_key"]

    logger.info(f"Emotion tagging full text for job {job_id}: s3://{text_bucket}/{text_key}")

    # Read full text from S3
    response = s3_client.get_object(Bucket=text_bucket, Key=text_key)
    original_text = response["Body"].read().decode("utf-8")

    logger.info(f"Read {len(original_text)} chars ({len(original_text.split())} words)")

    # Apply emotion tagging
    tagged_text = tagger.tag(original_text)

    # Count tags for metrics
    tag_counts = tagger.tag_count(tagged_text)
    total_tags = sum(tag_counts.values())
    logger.info(f"Inserted {total_tags} emotion tags: {tag_counts}")

    # Save tagged text to S3 alongside original
    tagged_key = f"{job_id}/tagged_full_text.txt"
    s3_client.put_object(
        Bucket=text_bucket,
        Key=tagged_key,
        Body=tagged_text.encode("utf-8"),
        ContentType="text/plain; charset=utf-8",
        Metadata={
            "job_id": job_id,
            "emotion_level": EMOTION_LEVEL,
            "emotion_tag_count": str(total_tags),
            "original_length": str(len(original_text)),
            "tagged_length": str(len(tagged_text)),
        },
    )

    logger.info(f"Saved tagged text to s3://{text_bucket}/{tagged_key}")

    # Forward to chunking queue with tagged text location
    chunking_message = {
        "job_id": job_id,
        "text_s3_bucket": text_bucket,
        "text_s3_key": tagged_key,
        "total_pages": message.get("total_pages", 0),
        "total_characters": len(tagged_text),
        "source_file": message.get("source_file", "unknown"),
        "emotion_tagged": True,
        "emotion_level": EMOTION_LEVEL,
        "emotion_tag_count": total_tags,
    }

    sqs_client.send_message(
        QueueUrl=CHUNKING_QUEUE_URL,
        MessageBody=json.dumps(chunking_message),
        MessageGroupId=job_id,
        MessageDeduplicationId=f"{job_id}-emotion-tagged",
    )

    logger.info(f"Forwarded to chunking queue for job {job_id}")

    return {
        "job_id": job_id,
        "tagged_s3_key": tagged_key,
        "emotion_level": EMOTION_LEVEL,
        "tag_counts": tag_counts,
        "total_tags": total_tags,
        "original_length": len(original_text),
        "tagged_length": len(tagged_text),
    }
