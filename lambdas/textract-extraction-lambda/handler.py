"""
Textract Extraction Lambda

Triggers: S3 event when PDF uploaded to OriginalFileBucket
Process:
  1. Start Textract async job
  2. Poll for completion
  3. Retrieve all blocks with pagination
  4. Save raw JSON to TextractResultsBucket/{job_id}/raw_response.json
  5. Extract text from LINE blocks
  6. Concatenate all pages
  7. Save to TextractResultsBucket/{job_id}/full_text.txt
  8. Send message to ChunkingQueue
"""

import json
import time
import os
import boto3
from datetime import datetime
from urllib.parse import unquote_plus

s3_client = boto3.client('s3')
textract_client = boto3.client('textract')
sqs_client = boto3.client('sqs')

# Environment variables
TEXTRACT_RESULTS_BUCKET = os.environ['TEXTRACT_RESULTS_BUCKET']
CHUNKING_QUEUE_URL = os.environ['CHUNKING_QUEUE_URL']

def lambda_handler(event, context):
    """
    Main handler for Textract extraction
    """
    print(f"Event: {json.dumps(event)}")

    # Extract S3 information from event
    for record in event['Records']:
        bucket = record['s3']['bucket']['name']
        key = unquote_plus(record['s3']['object']['key'])

        print(f"Processing file: s3://{bucket}/{key}")

        # Generate job_id from timestamp and filename
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        filename = key.split('/')[-1].replace('.pdf', '')
        job_id = f"{filename}-{timestamp}"

        try:
            # Start Textract job
            print(f"Starting Textract job for {key}")
            response = textract_client.start_document_text_detection(
                DocumentLocation={
                    'S3Object': {
                        'Bucket': bucket,
                        'Name': key
                    }
                }
            )

            textract_job_id = response['JobId']
            print(f"Textract Job ID: {textract_job_id}")

            # Poll for completion (5s intervals)
            print("Polling for job completion...")
            status = 'IN_PROGRESS'
            poll_count = 0
            max_polls = 180  # 15 minutes max (180 * 5s)

            while status == 'IN_PROGRESS' and poll_count < max_polls:
                time.sleep(5)
                poll_count += 1

                response = textract_client.get_document_text_detection(JobId=textract_job_id)
                status = response['JobStatus']
                print(f"Job status ({poll_count}): {status}")

                if status == 'FAILED':
                    error_msg = response.get('StatusMessage', 'Unknown error')
                    raise Exception(f"Textract job failed: {error_msg}")

            if status != 'SUCCEEDED':
                raise Exception(f"Textract job timed out after {poll_count * 5} seconds")

            print("Job succeeded! Retrieving results...")

            # Retrieve all blocks with pagination
            all_blocks = []
            next_token = None
            page_count = 0

            while True:
                if next_token:
                    response = textract_client.get_document_text_detection(
                        JobId=textract_job_id,
                        NextToken=next_token
                    )
                else:
                    response = textract_client.get_document_text_detection(JobId=textract_job_id)

                all_blocks.extend(response['Blocks'])
                page_count += 1
                print(f"Retrieved page {page_count} of results ({len(response['Blocks'])} blocks)")

                next_token = response.get('NextToken')
                if not next_token:
                    break

            print(f"Total blocks retrieved: {len(all_blocks)}")

            # Save raw JSON response
            raw_json_key = f"{job_id}/raw_response.json"
            print(f"Saving raw JSON to s3://{TEXTRACT_RESULTS_BUCKET}/{raw_json_key}")
            s3_client.put_object(
                Bucket=TEXTRACT_RESULTS_BUCKET,
                Key=raw_json_key,
                Body=json.dumps({
                    'textract_job_id': textract_job_id,
                    'source_bucket': bucket,
                    'source_key': key,
                    'timestamp': datetime.now().isoformat(),
                    'total_blocks': len(all_blocks),
                    'blocks': all_blocks[:100]  # Save first 100 blocks as sample
                }),
                ContentType='application/json'
            )

            # Extract text from LINE blocks
            print("Extracting text from blocks...")
            pages = {}

            for block in all_blocks:
                if block['BlockType'] == 'LINE':
                    page_num = block.get('Page', 1)
                    text = block.get('Text', '')

                    if page_num not in pages:
                        pages[page_num] = []

                    pages[page_num].append(text)

            # Concatenate all pages
            full_text_lines = []
            for page_num in sorted(pages.keys()):
                page_text = '\n'.join(pages[page_num])
                full_text_lines.append(page_text)

            full_text = '\n\n'.join(full_text_lines)  # Double newline between pages

            # Save full text
            full_text_key = f"{job_id}/full_text.txt"
            print(f"Saving full text to s3://{TEXTRACT_RESULTS_BUCKET}/{full_text_key}")
            print(f"Total pages: {len(pages)}, Total characters: {len(full_text)}")

            s3_client.put_object(
                Bucket=TEXTRACT_RESULTS_BUCKET,
                Key=full_text_key,
                Body=full_text.encode('utf-8'),
                ContentType='text/plain; charset=utf-8'
            )

            # Send message to chunking queue
            message = {
                'job_id': job_id,
                'text_s3_bucket': TEXTRACT_RESULTS_BUCKET,
                'text_s3_key': full_text_key,
                'total_pages': len(pages),
                'total_characters': len(full_text),
                'source_file': key
            }

            print(f"Sending message to chunking queue: {CHUNKING_QUEUE_URL}")
            sqs_client.send_message(
                QueueUrl=CHUNKING_QUEUE_URL,
                MessageBody=json.dumps(message),
                MessageGroupId=job_id,
                MessageDeduplicationId=f"{job_id}-extraction"
            )

            print(f"✓ Textract extraction complete for {job_id}")

            return {
                'statusCode': 200,
                'body': json.dumps({
                    'job_id': job_id,
                    'textract_job_id': textract_job_id,
                    'total_blocks': len(all_blocks),
                    'total_pages': len(pages),
                    'total_characters': len(full_text),
                    'raw_json_s3_key': raw_json_key,
                    'full_text_s3_key': full_text_key
                })
            }

        except Exception as e:
            print(f"✗ Error processing {key}: {str(e)}")
            import traceback
            traceback.print_exc()
            raise
