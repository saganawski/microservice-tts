#!/usr/bin/env python3
"""
Script to resend all TTS messages for a job
Reads chunks from S3 and sends messages to TTS queue
"""

import json
import boto3
from datetime import datetime

s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')

# Configuration
JOB_ID = "lore-test-textract-20251124071647-20251124-121651"
TEXT_CHUNKS_BUCKET = "text-chunks-bucket272765753210"
TTS_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/272765753210/tts-processing-queue.fifo"

def list_chunks_from_s3(bucket, job_id):
    """List all chunk files for a job"""
    prefix = f"{job_id}/"

    print(f"Listing chunks from s3://{bucket}/{prefix}")

    response = s3_client.list_objects_v2(
        Bucket=bucket,
        Prefix=prefix
    )

    chunks = []
    for obj in response.get('Contents', []):
        key = obj['Key']
        # Only get chunk_XXXX.txt files, skip metadata.json
        if key.endswith('.txt') and 'chunk_' in key:
            chunks.append(key)

    # Sort by chunk number
    chunks.sort()

    print(f"Found {len(chunks)} chunks")
    return chunks

def send_chunk_to_tts_queue(job_id, chunk_index, total_chunks, text_s3_key):
    """Send a chunk message to TTS queue"""
    message = {
        'job_id': job_id,
        'chunk_index': chunk_index,
        'total_chunks': total_chunks,
        'text_s3_bucket': TEXT_CHUNKS_BUCKET,
        'text_s3_key': text_s3_key,
        'source_file': 'lore.pdf'
    }

    print(f"Sending chunk {chunk_index+1}/{total_chunks}: {text_s3_key}")

    sqs_client.send_message(
        QueueUrl=TTS_QUEUE_URL,
        MessageBody=json.dumps(message),
        MessageGroupId=job_id,
        MessageDeduplicationId=f"{job_id}-chunk-{chunk_index:04d}-{datetime.now().timestamp()}"
    )

def main():
    print("=" * 60)
    print(f"Resending TTS messages for job: {JOB_ID}")
    print("=" * 60)

    # List all chunks
    chunks = list_chunks_from_s3(TEXT_CHUNKS_BUCKET, JOB_ID)

    if not chunks:
        print("ERROR: No chunks found!")
        return

    total_chunks = len(chunks)
    print(f"\nSending {total_chunks} messages to TTS queue...")

    # Send each chunk to TTS queue
    for idx, chunk_key in enumerate(chunks):
        send_chunk_to_tts_queue(JOB_ID, idx, total_chunks, chunk_key)

    print("\n" + "=" * 60)
    print(f"✓ Successfully sent {total_chunks} messages to TTS queue")
    print("=" * 60)

if __name__ == "__main__":
    main()
