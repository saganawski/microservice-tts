#!/usr/bin/env python3
"""
Chapter Text Extractor Lambda - Refactored
Extracts chapter text from Gemini and sends chunks to TTS queue
"""

import json
import boto3
import os
from datetime import datetime
from google import genai
from google.genai import types
from typing import Dict, Any, List
import logging
import tiktoken
import hashlib

# Configure logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Initialize clients
s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')
_genai_client = None

# Environment variables
GEMINI_API_KEY = os.environ['GEMINI_API_KEY']
TTS_QUEUE_URL = os.environ.get('TTS_QUEUE_URL')
CHUNKS_BUCKET = os.environ.get('CHUNKS_BUCKET', 'text-chunks-bucket272765753210')

# Model configuration
TEXT_EXTRACTION_MODEL = os.environ.get('GEMINI_TEXT_MODEL', 'gemini-3-pro-preview')

# Token limits
MAX_TOKENS_PER_CHUNK = 4500


def get_genai_client():
    """Lazy initialization of Gemini client"""
    global _genai_client
    if _genai_client is None:
        _genai_client = genai.Client(api_key=GEMINI_API_KEY)
    return _genai_client


def extract_chapter_text(google_file_uri: str, google_file_name: str,
                         chapter_info: Dict[str, Any]) -> str:
    """
    Extract text from specific chapter pages using Gemini

    Args:
        google_file_uri: URI of the uploaded file in Google's system
        google_file_name: Name of the file in Google's system
        chapter_info: Dict with chapter_number, title, start_page, end_page

    Returns:
        Extracted text content
    """
    chapter_num = chapter_info['chapter_number']
    title = chapter_info['title']
    start_page = chapter_info['start_page']
    end_page = chapter_info['end_page']

    logger.info(f"Extracting Chapter {chapter_num}: {title} (pages {start_page}-{end_page})")

    prompt = f"""Extract ALL the text from pages {start_page} to {end_page} of this document.

This is Chapter {chapter_num}: {title}
This an PDF document of a book. The start and end pages are of the count of the pages in pdf file not the printed page numbers.

**CRITICAL**: Return ONLY the actual text content from these pages. Do not add any commentary, explanations, or formatting.
Do not include page numbers, headers, or footers.
Just return the pure story/content text exactly as it appears in the document.

If there are any images, tables, or non-text elements on these pages, ignore them and do not mention them.

"""

    client = get_genai_client()

    response = client.models.generate_content(
        model=TEXT_EXTRACTION_MODEL,
        contents=[
            types.Content(
                role="user",
                parts=[
                    types.Part.from_uri(
                        file_uri=google_file_uri,
                        mime_type="application/pdf"
                    ),
                    types.Part.from_text(text=prompt)
                ]
            )
        ]
    )

    # Check if response has text attribute and it's not None
    if not hasattr(response, 'text') or response.text is None:
        logger.error(f"No text in response for Chapter {chapter_num}")
        raise ValueError(f"Gemini API returned no text for Chapter {chapter_num}")

    text = response.text.strip()

    if not text:
        logger.error(f"Empty text response for Chapter {chapter_num}")
        raise ValueError(f"Empty text extracted for Chapter {chapter_num}")

    logger.info(f"Extracted {len(text)} characters for Chapter {chapter_num}")

    # Log token usage
    if hasattr(response, 'usage_metadata'):
        usage = response.usage_metadata
        logger.info(
            f"Text extraction token usage - Input: {usage.prompt_token_count}, "
            f"Output: {usage.candidates_token_count}, "
            f"Total: {usage.total_token_count}"
        )

    return text


def chunk_text_by_tokens(text: str, max_tokens: int = MAX_TOKENS_PER_CHUNK) -> List[str]:
    """
    Chunk text into segments with max token count

    Args:
        text: Text to chunk
        max_tokens: Maximum tokens per chunk (default 4.5k)

    Returns:
        List of text chunks
    """
    # Use tiktoken for accurate token counting (cl100k_base is GPT-4 encoding)
    # This is a reasonable approximation for Gemini as well
    try:
        encoding = tiktoken.get_encoding("cl100k_base")
    except Exception as e:
        logger.warning(f"Failed to load tiktoken encoding: {e}, using character approximation")
        # Fallback: approximate 1 token ≈ 4 characters
        char_limit = max_tokens * 4
        chunks = []
        for i in range(0, len(text), char_limit):
            chunks.append(text[i:i + char_limit])
        logger.info(f"Created {len(chunks)} chunks using character approximation")
        return chunks

    tokens = encoding.encode(text)
    total_tokens = len(tokens)

    logger.info(f"Text has {total_tokens} tokens, chunking to max {max_tokens} tokens each")

    chunks = []
    for i in range(0, total_tokens, max_tokens):
        chunk_tokens = tokens[i:i + max_tokens]
        chunk_text = encoding.decode(chunk_tokens)
        chunks.append(chunk_text)

    logger.info(f"Created {len(chunks)} chunks")
    return chunks


def store_text_chunk(job_id: str, chapter_num: int, chunk_index: int,
                     chunk_text: str, total_chunks: int, chapter_title: str) -> str:
    """
    Store text chunk in S3 for later retrieval

    Returns:
        S3 key of stored chunk
    """
    # Generate consistent chunk ID
    chunk_id = f"{job_id}/chapter_{chapter_num:02d}/chunk_{chunk_index:03d}.txt"

    metadata = {
        'job_id': job_id,
        'chapter_number': str(chapter_num),
        'chapter_title': chapter_title,
        'chunk_index': str(chunk_index),
        'total_chunks': str(total_chunks),
        'text_length': str(len(chunk_text))
    }

    logger.info(f"Storing text chunk to s3://{CHUNKS_BUCKET}/{chunk_id}")

    s3_client.put_object(
        Bucket=CHUNKS_BUCKET,
        Key=chunk_id,
        Body=chunk_text.encode('utf-8'),
        ContentType='text/plain; charset=utf-8',
        Metadata=metadata
    )

    return chunk_id


def send_chunk_to_tts_queue(job_id: str, chapter_num: int, chapter_title: str,
                            chunk_index: int, total_chunks: int,
                            text_chunk_s3_key: str) -> None:
    """
    Send chunk to TTS processing queue
    """
    message = {
        'job_id': job_id,
        'chapter': {
            'number': chapter_num,
            'title': chapter_title
        },
        'chunk': {
            'index': chunk_index,
            'total': total_chunks,
            'text_s3_key': text_chunk_s3_key,
            'text_bucket': CHUNKS_BUCKET
        },
        'timestamp': datetime.now().isoformat()
    }

    logger.info(f"Sending chunk {chunk_index+1}/{total_chunks} to TTS queue")

    # Generate unique MessageDeduplicationId for FIFO queue
    dedup_id = f"{job_id}-ch{chapter_num:02d}-chunk{chunk_index:03d}"

    sqs_client.send_message(
        QueueUrl=TTS_QUEUE_URL,
        MessageBody=json.dumps(message),
        MessageGroupId=f"{job_id}-ch{chapter_num:02d}",  # Group chunks from same chapter together
        MessageDeduplicationId=dedup_id,  # Unique ID to prevent duplicates
        MessageAttributes={
            'job_id': {'StringValue': job_id, 'DataType': 'String'},
            'chapter_number': {'StringValue': str(chapter_num), 'DataType': 'Number'},
            'chunk_index': {'StringValue': str(chunk_index), 'DataType': 'Number'}
        }
    )


def lambda_handler(event: Dict[str, Any], context: Any) -> Dict[str, Any]:
    """
    Lambda handler for chapter text extraction with retry logic
    Triggered by SQS messages from orchestrator lambda

    Workflow:
    1. Extract chapter text from PDF using Gemini
    2. Chunk text into smaller segments
    3. Store chunks in S3
    4. Send each chunk to TTS queue for processing

    Retry Logic:
    - 429 RESOURCE_EXHAUSTED errors: Return to SQS for automatic retry with backoff
    - Other errors: Log and fail permanently (move to DLQ after 3 attempts)
    """
    records = event.get('Records', [])

    if not records:
        logger.warning("No SQS records found in event")
        return {
            'statusCode': 400,
            'body': json.dumps({'error': 'No SQS records'})
        }

    # Track failed message IDs for batch failure response
    batch_item_failures = []
    successful_records = 0

    for record in records:
        message_id = record.get('messageId')

        try:
            # Parse SQS message body
            message_body = json.loads(record['body'])

            job_id = message_body['job_id']
            google_file_uri = message_body['google_file_uri']
            google_file_name = message_body['google_file_name']
            chapter_info = message_body['chapter']

            chapter_num = chapter_info['chapter_number']
            chapter_title = chapter_info['title']

            logger.info(f"Processing Job {job_id}, Chapter {chapter_num}: {chapter_title}")

            # Step 1: Extract chapter text from Gemini
            chapter_text = extract_chapter_text(
                google_file_uri,
                google_file_name,
                chapter_info
            )

            if not chapter_text or len(chapter_text) < 10:
                logger.error(f"Extracted text is too short or empty for Chapter {chapter_num}")
                # Don't retry empty responses - likely content safety filter
                raise ValueError(f"Failed to extract meaningful text for Chapter {chapter_num}")

            # Step 2: Chunk text to 4.5k tokens
            text_chunks = chunk_text_by_tokens(chapter_text, MAX_TOKENS_PER_CHUNK)
            logger.info(f"Chapter {chapter_num} split into {len(text_chunks)} chunks")

            # Step 3: Store chunks and send to TTS queue
            chunk_keys = []
            for i, chunk in enumerate(text_chunks):
                # Store chunk in S3
                chunk_key = store_text_chunk(
                    job_id, chapter_num, i, chunk,
                    len(text_chunks), chapter_title
                )
                chunk_keys.append(chunk_key)

                # Send to TTS queue
                send_chunk_to_tts_queue(
                    job_id, chapter_num, chapter_title,
                    i, len(text_chunks), chunk_key
                )

            # Store chapter metadata for later stitching
            metadata_key = f"{job_id}/chapter_{chapter_num:02d}/metadata.json"
            chapter_metadata = {
                'job_id': job_id,
                'chapter_number': chapter_num,
                'chapter_title': chapter_title,
                'total_chunks': len(text_chunks),
                'chunk_keys': chunk_keys,
                'status': 'chunks_queued'
            }

            s3_client.put_object(
                Bucket=CHUNKS_BUCKET,
                Key=metadata_key,
                Body=json.dumps(chapter_metadata, indent=2),
                ContentType='application/json'
            )

            successful_records += 1
            logger.info(f"Successfully queued {len(text_chunks)} chunks for Chapter {chapter_num}")

        except Exception as e:
            error_str = str(e)
            logger.error(f"Error processing Chapter {chapter_num}: {error_str}", exc_info=True)

            # Check if this is a retryable error (429 quota exhaustion)
            if '429' in error_str or 'RESOURCE_EXHAUSTED' in error_str or 'quota' in error_str.lower():
                logger.warning(f"API quota exhausted for Chapter {chapter_num}, will retry after backoff")
                # Add to batch failures - SQS will automatically retry with exponential backoff
                batch_item_failures.append({'itemIdentifier': message_id})
            else:
                # Non-retryable error (empty response, invalid data, etc.)
                logger.error(f"Non-retryable error for Chapter {chapter_num}, will not retry")
                # Don't add to batch failures - let it fail and move to DLQ after max retries

    # Return batch failure response for SQS to handle retries
    response = {
        'batchItemFailures': batch_item_failures
    }

    logger.info(f"Batch processing complete: {successful_records} successful, {len(batch_item_failures)} will retry")

    return response