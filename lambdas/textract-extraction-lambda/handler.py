"""
Text Extraction Lambda

Triggers: S3 event when file uploaded to OriginalFileBucket
Process:
  For PDF files:
    1. Start Textract async job
    2. Poll for completion
    3. Retrieve all blocks with pagination
    4. Save raw JSON to TextractResultsBucket/{job_id}/raw_response.json
    5. Extract text from LINE blocks
    6. Concatenate all pages
  For EPUB files:
    1. Download EPUB from S3
    2. Parse with ebooklib, reading spine order
    3. Skip front/back matter chapters
    4. Extract text from HTML content with BeautifulSoup
  Common:
    - Save to TextractResultsBucket/{job_id}/full_text.txt
    - Send message to ChunkingQueue
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
dynamodb = boto3.resource('dynamodb')

# Environment variables
TEXTRACT_RESULTS_BUCKET = os.environ['TEXTRACT_RESULTS_BUCKET']
# Pipeline routing: emotion tagging is only used for providers that support it (Orpheus).
# For MOSS/Gemini, skip emotion and go straight to chunking.
EMOTION_QUEUE_URL = os.environ.get('EMOTION_QUEUE_URL', '')
CHUNKING_QUEUE_URL = os.environ.get('CHUNKING_QUEUE_URL', '')
SKIP_EMOTION = os.environ.get('SKIP_EMOTION', 'false').lower() == 'true'
if SKIP_EMOTION or not EMOTION_QUEUE_URL:
    NEXT_QUEUE_URL = CHUNKING_QUEUE_URL
else:
    NEXT_QUEUE_URL = EMOTION_QUEUE_URL
JOB_STATUS_TABLE = os.environ.get('JOB_STATUS_TABLE', '')


def update_job_status(job_id, status, **kwargs):
    """Update job status in DynamoDB"""
    if not JOB_STATUS_TABLE:
        return
    try:
        table = dynamodb.Table(JOB_STATUS_TABLE)
        item = {
            'job_id': job_id,
            'status': status,
            'updated_at': datetime.now().isoformat(),
        }
        item.update(kwargs)
        # Use update to merge fields
        update_expr = 'SET #s = :s, updated_at = :u'
        expr_values = {':s': status, ':u': item['updated_at']}
        expr_names = {'#s': 'status'}
        for k, v in kwargs.items():
            safe_key = k.replace('-', '_')
            update_expr += f', {safe_key} = :{safe_key}'
            expr_values[f':{safe_key}'] = v
        table.update_item(
            Key={'job_id': job_id},
            UpdateExpression=update_expr,
            ExpressionAttributeValues=expr_values,
            ExpressionAttributeNames=expr_names,
        )
    except Exception as e:
        print(f"Warning: failed to update job status: {e}")

import re

# --- EPUB boilerplate detection constants (layered strategy) ---

# Layer 1: Manifest/spine item IDs to skip entirely
SKIP_MANIFEST_IDS = {
    'pg-header', 'pg-footer',
    'coverpage-wrapper', 'cover',
    'ncx', 'ncx2', 'toc',
}

# Layer 2: epub:type values to skip
SKIP_EPUB_TYPES = {
    'titlepage', 'halftitlepage',
    'imprint', 'colophon',
    'copyright-page',
    'cover',
    'toc', 'landmarks', 'loi', 'lot',
    'appendix', 'glossary', 'index',
    'bibliography',
}

# Layer 3: CSS classes indicating boilerplate
SKIP_CLASSES = {
    'pg-boilerplate', 'pgheader',
    'x-ebookmaker-coverpage',
}

# Layer 4: Filename patterns to skip
SKIP_FILENAME_PATTERNS = [
    r'cover\.',
    r'titlepage\.',
    r'halftitlepage\.',
    r'colophon\.',
    r'imprint\.',
    r'uncopyright\.',
    r'copyright\.',
    r'toc\.',
    r'wrap\d+\.',
]

# Layer 5: Text patterns indicating boilerplate
SKIP_TEXT_PATTERNS = [
    r'\*\*\* START OF THE PROJECT GUTENBERG',
    r'\*\*\* END OF THE PROJECT GUTENBERG',
    r'THE FULL PROJECT GUTENBERG.*LICENSE',
    r'This eBook is for the use of anyone anywhere',
    r'Project Gutenberg Literary Archive Foundation',
    r'produced by .* Distributed Proofreading',
    r'www\.gutenberg\.org',
]

# Element IDs for PG inline boilerplate (strip these elements, don't skip whole file)
PG_BOILERPLATE_IDS = {
    'pg-header', 'pg-footer',
    'pg-start-separator', 'pg-end-separator',
    'pg-machine-header',
    'project-gutenberg-license',
}

# Minimum visible text length to keep a spine item
MIN_CONTENT_LENGTH = 50


def _get_epub_types(soup):
    """Collect all epub:type values from body and top-level sections."""
    types = set()
    for tag in soup.find_all(['body', 'section'], limit=5):
        val = tag.get('epub:type', '')
        types.update(val.split())
    return types


def _get_classes(soup):
    """Collect CSS classes from body and top-level sections."""
    classes = set()
    for tag in soup.find_all(['body', 'section'], limit=5):
        classes.update(tag.get('class', []))
    return classes


def _has_pg_boilerplate_elements(soup):
    """Check if the HTML contains any PG boilerplate element IDs."""
    for eid in PG_BOILERPLATE_IDS:
        if soup.find(id=eid):
            return True
    return False


def _strip_pg_boilerplate(soup):
    """Remove PG boilerplate sections from parsed HTML, keeping book content."""
    for el in soup.find_all(class_='pg-boilerplate'):
        el.decompose()
    for eid in PG_BOILERPLATE_IDS:
        el = soup.find(id=eid)
        if el:
            el.decompose()
    return soup


def _should_skip_epub_item(item_id, soup, filename):
    """
    Layered boilerplate detection. Returns:
      'skip'  — skip this item entirely
      'strip' — strip PG boilerplate elements, then use remaining text
      'keep'  — use as-is
    """
    # Layer 1: Manifest ID
    if item_id.lower() in SKIP_MANIFEST_IDS:
        # PG header may contain actual title page content mixed with boilerplate
        if item_id.lower() in ('pg-header',) and _has_pg_boilerplate_elements(soup):
            return 'strip'
        return 'skip'

    # Layer 2: epub:type
    epub_types = _get_epub_types(soup)
    if epub_types & SKIP_EPUB_TYPES:
        return 'skip'

    # Layer 3: CSS classes
    classes = _get_classes(soup)
    if classes & SKIP_CLASSES:
        return 'skip'

    # Layer 4: Filename
    basename = os.path.basename(filename)
    for pattern in SKIP_FILENAME_PATTERNS:
        if re.match(pattern, basename, re.IGNORECASE):
            return 'skip'

    # Layer 5: Text pattern check (need >=2 matches in short content)
    text = soup.get_text(separator='\n', strip=True)
    boilerplate_hits = sum(1 for p in SKIP_TEXT_PATTERNS if re.search(p, text, re.IGNORECASE))
    if boilerplate_hits >= 2 and len(text) < 5000:
        return 'skip'

    # Layer 6: Minimum content threshold
    if len(text.strip()) < MIN_CONTENT_LENGTH:
        return 'skip'

    # Check for inline PG boilerplate that should be stripped
    if _has_pg_boilerplate_elements(soup):
        return 'strip'

    return 'keep'


def extract_epub_text(epub_path):
    """
    Parse an EPUB file and extract chapter text in spine order,
    filtering boilerplate with layered detection.
    Returns the concatenated text.
    """
    import ebooklib
    from ebooklib import epub
    from bs4 import BeautifulSoup

    book = epub.read_epub(epub_path)

    chapter_texts = []
    for spine_id, linear in book.spine:
        item = book.get_item_with_id(spine_id)
        if item is None:
            continue
        if item.get_type() != ebooklib.ITEM_DOCUMENT:
            continue

        html_content = item.get_content().decode('utf-8', errors='replace')
        soup = BeautifulSoup(html_content, 'html.parser')

        action = _should_skip_epub_item(spine_id, soup, item.get_name() or '')

        if action == 'skip':
            print(f"EPUB skip: {item.get_name()} (id: {spine_id})")
            continue

        if action == 'strip':
            print(f"EPUB strip boilerplate: {item.get_name()} (id: {spine_id})")
            soup = _strip_pg_boilerplate(soup)

        text = soup.get_text(separator='\n', strip=True)
        if len(text.strip()) >= MIN_CONTENT_LENGTH:
            chapter_texts.append(text)

    return '\n\n'.join(chapter_texts)


def extract_text_from_pdf(bucket, key, job_id):
    """Extract text from PDF using AWS Textract. Returns (full_text, extra_info)."""
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
            'blocks': all_blocks[:100]
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

    full_text = '\n\n'.join(full_text_lines)

    extra_info = {
        'textract_job_id': textract_job_id,
        'total_blocks': len(all_blocks),
        'total_pages': len(pages),
        'raw_json_s3_key': raw_json_key,
    }
    return full_text, extra_info


def lambda_handler(event, context):
    """
    Main handler for text extraction (PDF via Textract, EPUB via ebooklib)
    """
    print(f"Event: {json.dumps(event)}")

    # Extract S3 information from event
    for record in event['Records']:
        bucket = record['s3']['bucket']['name']
        key = unquote_plus(record['s3']['object']['key'])

        print(f"Processing file: s3://{bucket}/{key}")

        # Try to get job_id and voice from S3 object metadata (set by FileValidationLambda)
        try:
            head = s3_client.head_object(Bucket=bucket, Key=key)
            metadata = head.get('Metadata', {})
            job_id = metadata.get('job_id', '')
            voice = metadata.get('voice', '')
        except Exception:
            job_id = ''
            voice = ''

        # Fallback: derive job_id from filename
        if not job_id:
            timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            filename = key.split('/')[-1].rsplit('.', 1)[0]
            job_id = f"{filename}-{timestamp}"

        print(f"Job ID: {job_id}, Voice: {voice or 'default'}")

        # Write initial UPLOADED status then transition to EXTRACTING
        update_job_status(job_id, 'UPLOADED', progress=0,
                          file_name=key.split('/')[-1],
                          created_at=datetime.now().isoformat())
        update_job_status(job_id, 'EXTRACTING', progress=5)

        try:
            # Detect file type from extension
            file_ext = key.rsplit('.', 1)[-1].lower() if '.' in key else ''

            if file_ext == 'epub':
                # EPUB: parse locally with ebooklib, skip Textract entirely
                print(f"Detected EPUB file, parsing with ebooklib...")
                local_path = f"/tmp/{job_id}.epub"
                s3_client.download_file(bucket, key, local_path)
                full_text = extract_epub_text(local_path)
                # Clean up temp file
                os.remove(local_path)
                extra_info = {'total_pages': 0}
                print(f"EPUB extracted: {len(full_text)} characters")
            else:
                # PDF (and other types): use Textract
                full_text, extra_info = extract_text_from_pdf(bucket, key, job_id)

            # Save full text
            full_text_key = f"{job_id}/full_text.txt"
            print(f"Saving full text to s3://{TEXTRACT_RESULTS_BUCKET}/{full_text_key}")
            print(f"Total characters: {len(full_text)}")

            s3_client.put_object(
                Bucket=TEXTRACT_RESULTS_BUCKET,
                Key=full_text_key,
                Body=full_text.encode('utf-8'),
                ContentType='text/plain; charset=utf-8'
            )

            # Send message to next queue (emotion tagging or chunking)
            message = {
                'job_id': job_id,
                'text_s3_bucket': TEXTRACT_RESULTS_BUCKET,
                'text_s3_key': full_text_key,
                'total_pages': extra_info.get('total_pages', 0),
                'total_characters': len(full_text),
                'source_file': key,
            }
            if voice:
                message['voice'] = voice

            queue_name = "emotion queue" if EMOTION_QUEUE_URL else "chunking queue"
            print(f"Sending message to {queue_name}: {NEXT_QUEUE_URL}")
            sqs_client.send_message(
                QueueUrl=NEXT_QUEUE_URL,
                MessageBody=json.dumps(message),
                MessageGroupId=job_id,
                MessageDeduplicationId=f"{job_id}-extraction"
            )

            update_job_status(job_id, 'EXTRACTING', progress=15)
            print(f"✓ Extraction complete for {job_id}")

            result = {
                'job_id': job_id,
                'total_characters': len(full_text),
                'full_text_s3_key': full_text_key,
                'file_type': file_ext,
            }
            result.update(extra_info)

            return {
                'statusCode': 200,
                'body': json.dumps(result)
            }

        except Exception as e:
            print(f"✗ Error processing {key}: {str(e)}")
            update_job_status(job_id, 'FAILED', error_message=str(e)[:500])
            import traceback
            traceback.print_exc()
            raise
