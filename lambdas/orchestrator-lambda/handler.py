import json
import os
import time
import boto3
import unicodedata
from google import genai
from google.genai import types

s3 = boto3.client('s3')
sqs = boto3.client('sqs')

GEMINI_API_KEY = os.environ['GEMINI_API_KEY']
CHAPTER_QUEUE_URL = os.environ['CHAPTER_QUEUE_URL']
TEXT_BUCKET = os.environ['TEXT_BUCKET']


def sanitize_for_ascii(text):
    """
    Convert text to ASCII-safe format for S3 metadata.
    Removes accents and special characters.

    Args:
        text: Original text that may contain non-ASCII characters

    Returns:
        ASCII-safe version of the text
    """
    # Normalize to decomposed form (separates base characters from accents)
    normalized = unicodedata.normalize('NFKD', text)
    # Keep only ASCII characters
    ascii_text = normalized.encode('ascii', 'ignore').decode('ascii')

    # Clean up any extra spaces
    ascii_text = ' '.join(ascii_text.split())

    # If the result is empty, use a placeholder
    if not ascii_text.strip():
        return "Chapter"

    return ascii_text


def lambda_handler(event, context):

    bucket = event['Records'][0]['s3']['bucket']['name']
    key = event['Records'][0]['s3']['object']['key']

    print(f"Processing: s3://{bucket}/{key}")

    local_path = f"/tmp/{os.path.basename(key)}"
    s3.download_file(bucket, key, local_path)

    client = genai.Client(api_key=GEMINI_API_KEY)

    print("Uploading file to Google file API")
    # Gemini Files API limits for PDFs: max 50MB or 1000 pages
    # Files larger than these limits will return 400 INVALID_ARGUMENT error
    # Consider compressing or splitting large PDFs before processing
    # Upload the file directly
    uploaded_file = client.files.upload(file=local_path)

    print(f"Upload file URI: {uploaded_file.uri}")

    file = client.files.get(name=uploaded_file.name)
    while file.state == "PROCESSING":
        print("Waiting for the file processing ...")
        time.sleep(2)
        file = client.files.get(name=uploaded_file.name)

    if file.state == "FAILED":
        raise Exception("File processing failed")

    print("Analyzing book structure with Gemini AI ...")

    prompt = """Analyze this book PDF and identify all chapters.
        ignore all the text that doesnt have anything to do with the story, like copeyright info, or glossary, or outline, anything like that.
        **CRITICAL**: the purpose is to split the book into chapters for further processing of this file. later we will extract text from each chapter based on the page numbers you provide here.
        it's more important to have the correct page numbers in the file then what is said on the page. For example if the page says its number 5 but in reality its page 3 of  the file you are given mark this as page 3.

        Return ONLY a JSON array with this structure:
        [
        {
            "chapter_number": 1,
            "title": "Introduction",
            "start_page": 1,
            "end_page": 15
        },
        {
            "chapter_number": 2,
            "title": "Getting Started",
            "start_page": 16,
            "end_page": 45
        }
        ]

        Be precise with page numbers. Only return the JSON array, no additional text."""

    response = client.models.generate_content(
        model="gemini-3-pro-preview",
        contents=[uploaded_file, prompt]
    )

    chapters_text = response.text.strip()
    print(chapters_text)
    # Remove markdown code blocks if present
    if chapters_text.startswith('```'):
        chapters_text = chapters_text.split('```')[1]
        if chapters_text.startswith('json'):
            chapters_text = chapters_text[4:]

    chapters = json.loads(chapters_text)
    print(f"Found {len(chapters)} chapters")

    # Sanitize chapter titles for S3 metadata compatibility
    for chapter in chapters:
        original_title = chapter['title']
        chapter['sanitized_title'] = sanitize_for_ascii(original_title)
        # Keep original title for reference
        chapter['original_title'] = original_title
        print(f"Chapter {chapter['chapter_number']}: '{original_title}' -> '{chapter['sanitized_title']}'")

    job_id = os.path.basename(key).replace('.pdf', '')
    job_metadata = {
        'job_id': job_id,
        'source_file_uri': f"s3://{bucket}/{key}",
        'google_file': uploaded_file.uri,
        'google_file_name': uploaded_file.name,
        'total_chapters': len(chapters),
        'chapters': chapters,
        'status': 'processing'
    }

    # Store metadata in S3
    metadata_key = f"jobs/{job_id}/metadata.json"
    s3.put_object(
        Bucket=TEXT_BUCKET,
        Key=metadata_key,
        Body=json.dumps(job_metadata, indent=2, ensure_ascii=False),
        ContentType='application/json'
    )

    # Send chapter extraction jobs to SQS
    for chapter in chapters:
        # Create a chapter dict with sanitized title for the SQS message
        sanitized_chapter = {
            'chapter_number': chapter['chapter_number'],
            'title': chapter['sanitized_title'],  # Use sanitized title
            'original_title': chapter.get('original_title', chapter['title']),
            'start_page': chapter['start_page'],
            'end_page': chapter['end_page']
        }

        message = {
            'job_id': job_id,
            'google_file_uri': uploaded_file.uri,
            'google_file_name': uploaded_file.name,
            'chapter': sanitized_chapter,
            'text_bucket': TEXT_BUCKET
        }

        sqs.send_message(
            QueueUrl=CHAPTER_QUEUE_URL,
            MessageBody=json.dumps(message),
            MessageGroupId=f"{job_id}-ch{chapter['chapter_number']:02d}"  # For FIFO queue - unique per chapter
        )
        print(f"Queued chapter {chapter['chapter_number']}: {
              chapter['sanitized_title']}")

    return {
        'statusCode': 200,
        'body': json.dumps({
            'job_id': job_id,
            'chapters': len(chapters)
        })
    }