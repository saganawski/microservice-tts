#!/usr/bin/env python3
"""
One-off script to test AWS Textract on lore PDF
Tests theory: Can Textract extract text that Gemini can't?
"""

import boto3
import time
import json
from datetime import datetime

# Initialize AWS clients
s3_client = boto3.client('s3')
textract_client = boto3.client('textract', region_name='us-east-1')

# Configuration
BUCKET_NAME = 'original-file-bucket272765753210'
PDF_KEY = 'lore-book-trimmed-2-compressed.pdf'  # The lore PDF we've been testing
OUTPUT_FILE = '/tmp/textract_lore_results.json'
OUTPUT_TEXT_FILE = '/tmp/textract_lore_text.txt'

def start_textract_job(bucket, key):
    """
    Start async Textract job for larger PDFs
    Best practice for files > 1 page
    """
    print(f"Starting Textract job for s3://{bucket}/{key}")

    response = textract_client.start_document_text_detection(
        DocumentLocation={
            'S3Object': {
                'Bucket': bucket,
                'Name': key
            }
        }
    )

    job_id = response['JobId']
    print(f"Textract Job ID: {job_id}")
    return job_id


def get_textract_results(job_id):
    """
    Poll Textract job until complete, then retrieve all results
    Handles pagination for large documents
    """
    print(f"Polling for job completion: {job_id}")

    # Poll for completion
    while True:
        response = textract_client.get_document_text_detection(JobId=job_id)
        status = response['JobStatus']

        print(f"Job status: {status}")

        if status == 'SUCCEEDED':
            break
        elif status == 'FAILED':
            raise Exception(f"Textract job failed: {response.get('StatusMessage', 'Unknown error')}")

        # Wait before polling again
        time.sleep(5)

    # Collect all results (handle pagination)
    print("Collecting results...")
    all_blocks = []
    next_token = None
    page_count = 0

    while True:
        if next_token:
            response = textract_client.get_document_text_detection(
                JobId=job_id,
                NextToken=next_token
            )
        else:
            response = textract_client.get_document_text_detection(JobId=job_id)

        all_blocks.extend(response['Blocks'])
        page_count += 1
        print(f"Retrieved page {page_count} of results ({len(response['Blocks'])} blocks)")

        next_token = response.get('NextToken')
        if not next_token:
            break

    print(f"Total blocks retrieved: {len(all_blocks)}")
    return all_blocks, response


def extract_text_from_blocks(blocks):
    """
    Extract plain text from Textract blocks
    Maintains page structure
    """
    print("Extracting text from blocks...")

    # Group by page
    pages = {}
    for block in blocks:
        if block['BlockType'] == 'PAGE':
            page_num = block.get('Page', 1)
            pages[page_num] = {
                'blocks': [],
                'text': []
            }

    # Add LINE blocks to pages
    for block in blocks:
        if block['BlockType'] == 'LINE':
            page_num = block.get('Page', 1)
            text = block.get('Text', '')
            if page_num in pages:
                pages[page_num]['text'].append(text)

    # Combine all text
    full_text = []
    for page_num in sorted(pages.keys()):
        page_text = '\n'.join(pages[page_num]['text'])
        full_text.append(f"--- Page {page_num} ---\n{page_text}\n")

    return '\n'.join(full_text), pages


def analyze_results(blocks, pages):
    """
    Analyze extraction results
    """
    print("\n" + "="*60)
    print("TEXTRACT ANALYSIS")
    print("="*60)

    # Count block types
    block_types = {}
    for block in blocks:
        block_type = block['BlockType']
        block_types[block_type] = block_types.get(block_type, 0) + 1

    print("\nBlock Types:")
    for block_type, count in sorted(block_types.items()):
        print(f"  {block_type}: {count}")

    print(f"\nTotal Pages: {len(pages)}")

    # Analyze text per page
    total_chars = 0
    total_lines = 0
    for page_num in sorted(pages.keys()):
        page_lines = len(pages[page_num]['text'])
        page_text = '\n'.join(pages[page_num]['text'])
        page_chars = len(page_text)
        total_chars += page_chars
        total_lines += page_lines
        print(f"  Page {page_num}: {page_lines} lines, {page_chars} characters")

    print(f"\nTotal Lines: {total_lines}")
    print(f"Total Characters: {total_chars}")
    print(f"Average chars/page: {total_chars // len(pages) if pages else 0}")


def main():
    """
    Main execution
    """
    print("="*60)
    print("AWS TEXTRACT - LORE PDF EXTRACTION TEST")
    print("="*60)
    print(f"PDF: s3://{BUCKET_NAME}/{PDF_KEY}")
    print(f"Started: {datetime.now().isoformat()}")
    print()

    # Check if PDF exists
    try:
        s3_client.head_object(Bucket=BUCKET_NAME, Key=PDF_KEY)
        print("✓ PDF found in S3")
    except Exception as e:
        print(f"✗ PDF not found: {e}")
        # Try to find the lore PDF
        print("\nSearching for lore PDF...")
        response = s3_client.list_objects_v2(Bucket=BUCKET_NAME, Prefix='lore')
        if 'Contents' in response:
            print("Found lore PDFs:")
            for obj in response['Contents']:
                print(f"  - {obj['Key']}")
        return

    try:
        # Start Textract job
        job_id = start_textract_job(BUCKET_NAME, PDF_KEY)

        # Get results
        blocks, final_response = get_textract_results(job_id)

        # Extract text
        full_text, pages = extract_text_from_blocks(blocks)

        # Save raw JSON results
        print(f"\nSaving raw results to: {OUTPUT_FILE}")
        with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
            json.dump({
                'job_id': job_id,
                'bucket': BUCKET_NAME,
                'key': PDF_KEY,
                'timestamp': datetime.now().isoformat(),
                'total_blocks': len(blocks),
                'total_pages': len(pages),
                'blocks': blocks[:100],  # Save first 100 blocks as sample
                'job_metadata': {
                    'JobStatus': final_response.get('JobStatus'),
                    'DocumentMetadata': final_response.get('DocumentMetadata'),
                }
            }, f, indent=2)

        # Save extracted text
        print(f"Saving extracted text to: {OUTPUT_TEXT_FILE}")
        with open(OUTPUT_TEXT_FILE, 'w', encoding='utf-8') as f:
            f.write(full_text)

        # Analyze results
        analyze_results(blocks, pages)

        # Show sample text
        print("\n" + "="*60)
        print("SAMPLE TEXT (First 1000 characters)")
        print("="*60)
        print(full_text[:1000])
        print("...")

        print("\n" + "="*60)
        print("EXTRACTION COMPLETE")
        print("="*60)
        print(f"✓ Raw JSON saved to: {OUTPUT_FILE}")
        print(f"✓ Extracted text saved to: {OUTPUT_TEXT_FILE}")
        print(f"✓ Total pages: {len(pages)}")
        print(f"✓ Total characters: {len(full_text)}")
        print(f"Completed: {datetime.now().isoformat()}")

    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()


if __name__ == '__main__':
    main()
