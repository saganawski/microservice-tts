"""
Text Chunking Lambda

Triggers: SQS message from ChunkingQueue (after Textract extraction)
Process:
  1. Download full text from S3
  2. Split into token-limited chunks at sentence boundaries
     - Gemini: 4500 tokens (default)
     - Orpheus: 1500 tokens (set via TTS_PROVIDER or CHUNK_SIZE_TOKENS)
  3. Save chunks to TextChunksBucket/{job_id}/chunk_{XXX}.txt
  4. Store metadata: total_chunks, tts_provider, chunk_size_tokens
  5. Send each chunk to TTSQueue
"""

import json
import os
import boto3
from datetime import datetime

s3_client = boto3.client('s3')
sqs_client = boto3.client('sqs')
dynamodb = boto3.resource('dynamodb')

# Environment variables
TEXTRACT_RESULTS_BUCKET = os.environ['TEXTRACT_RESULTS_BUCKET']
TEXT_CHUNKS_BUCKET = os.environ['TEXT_CHUNKS_BUCKET']
TTS_QUEUE_URL = os.environ['TTS_QUEUE_URL']
JOB_STATUS_TABLE = os.environ.get('JOB_STATUS_TABLE', '')
TTS_PROVIDER = os.environ.get('TTS_PROVIDER', 'gemini').lower()

# Chunk size: explicit env var > provider-based default
# MOSS handles ~800 words fine; 500 words (~670 tokens) is the sweet spot
# Orpheus limited to 127 words (~170 tokens) due to 8192 output token cap
_CHUNK_SIZE_ENV = os.environ.get('CHUNK_SIZE_TOKENS', '')
if _CHUNK_SIZE_ENV:
    MAX_TOKENS_PER_CHUNK = int(_CHUNK_SIZE_ENV)
elif TTS_PROVIDER == 'moss':
    MAX_TOKENS_PER_CHUNK = 670  # ~500 words — tested 162 WPM, no truncation
elif TTS_PROVIDER == 'orpheus':
    MAX_TOKENS_PER_CHUNK = 170  # ~127 words — hard limit from 8192 output tokens
else:
    MAX_TOKENS_PER_CHUNK = int(os.environ.get('MAX_TOKENS_PER_CHUNK', '4500'))

print(f"TTS provider: {TTS_PROVIDER}, chunk size: {MAX_TOKENS_PER_CHUNK} tokens")


def update_job_status(job_id, status, **kwargs):
    if not JOB_STATUS_TABLE:
        return
    try:
        table = dynamodb.Table(JOB_STATUS_TABLE)
        update_expr = 'SET #s = :s, updated_at = :u'
        expr_values = {':s': status, ':u': datetime.now().isoformat()}
        expr_names = {'#s': 'status'}
        for k, v in kwargs.items():
            update_expr += f', {k} = :{k}'
            expr_values[f':{k}'] = v
        table.update_item(
            Key={'job_id': job_id},
            UpdateExpression=update_expr,
            ExpressionAttributeValues=expr_values,
            ExpressionAttributeNames=expr_names,
        )
    except Exception as e:
        print(f"Warning: failed to update job status: {e}")

# Try to import tiktoken, fallback to character-based chunking
try:
    import tiktoken
    TIKTOKEN_AVAILABLE = True
    print("Using tiktoken for accurate token counting")
except ImportError:
    TIKTOKEN_AVAILABLE = False
    print("Tiktoken not available, using character-based estimation")

def _split_sentences(text):
    """Split text into sentences, keeping the delimiter attached."""
    import re
    parts = re.split(r'(?<=[.!?])\s+', text)
    return [p for p in parts if p.strip()]


def chunk_text_with_tiktoken(text, max_tokens=4500):
    """
    Chunk text using tiktoken with sentence-boundary splitting.
    Never breaks mid-sentence — each chunk ends at a sentence boundary.
    """
    encoding = tiktoken.get_encoding("cl100k_base")
    sentences = _split_sentences(text)

    chunks = []
    current_sentences = []
    current_tokens = 0

    for sentence in sentences:
        sentence_tokens = len(encoding.encode(sentence))

        # If a single sentence exceeds max_tokens, it becomes its own chunk
        if sentence_tokens > max_tokens:
            if current_sentences:
                chunks.append(' '.join(current_sentences))
                current_sentences = []
                current_tokens = 0
            chunks.append(sentence)
            continue

        # Would adding this sentence exceed the limit?
        # +1 accounts for the space joining sentences
        projected = current_tokens + sentence_tokens + (1 if current_sentences else 0)
        if projected > max_tokens and current_sentences:
            chunks.append(' '.join(current_sentences))
            current_sentences = []
            current_tokens = 0

        current_sentences.append(sentence)
        current_tokens += sentence_tokens + (1 if len(current_sentences) > 1 else 0)

    if current_sentences:
        chunks.append(' '.join(current_sentences))

    return chunks

def chunk_text_by_characters(text, max_tokens=4500):
    """
    Fallback: Chunk text by estimated characters with sentence boundaries.
    Rough estimate: 1 token ≈ 4 characters.
    """
    max_chars = max_tokens * 4
    sentences = _split_sentences(text)

    chunks = []
    current_sentences = []
    current_chars = 0

    for sentence in sentences:
        sentence_chars = len(sentence)

        if sentence_chars > max_chars:
            if current_sentences:
                chunks.append(' '.join(current_sentences))
                current_sentences = []
                current_chars = 0
            chunks.append(sentence)
            continue

        projected = current_chars + sentence_chars + (1 if current_sentences else 0)
        if projected > max_chars and current_sentences:
            chunks.append(' '.join(current_sentences))
            current_sentences = []
            current_chars = 0

        current_sentences.append(sentence)
        current_chars += sentence_chars + (1 if len(current_sentences) > 1 else 0)

    if current_sentences:
        chunks.append(' '.join(current_sentences))

    return chunks

def lambda_handler(event, context):
    """
    Main handler for text chunking
    """
    print(f"Event: {json.dumps(event)}")

    # Process SQS messages
    for record in event['Records']:
        message_body = json.loads(record['body'])
        print(f"Processing message: {json.dumps(message_body)}")

        job_id = message_body['job_id']
        text_s3_bucket = message_body['text_s3_bucket']
        text_s3_key = message_body['text_s3_key']
        voice = message_body.get('voice')

        # Generate a deterministic seed per job for voice consistency across chunks
        seed = hash(job_id) % (2**31)

        try:
            # Download full text from S3
            print(f"Downloading text from s3://{text_s3_bucket}/{text_s3_key}")
            response = s3_client.get_object(Bucket=text_s3_bucket, Key=text_s3_key)
            full_text = response['Body'].read().decode('utf-8')

            print(f"Text length: {len(full_text)} characters")

            update_job_status(job_id, 'CHUNKING', progress=20)

            # Chunk the text
            print(f"Chunking text with max {MAX_TOKENS_PER_CHUNK} tokens per chunk...")
            if TIKTOKEN_AVAILABLE:
                chunks = chunk_text_with_tiktoken(full_text, max_tokens=MAX_TOKENS_PER_CHUNK)
            else:
                chunks = chunk_text_by_characters(full_text, max_tokens=MAX_TOKENS_PER_CHUNK)

            print(f"Created {len(chunks)} chunks")

            # Save chunks to S3
            for idx, chunk_text in enumerate(chunks):
                chunk_key = f"{job_id}/chunk_{idx:04d}.txt"
                print(f"Saving chunk {idx+1}/{len(chunks)} to s3://{TEXT_CHUNKS_BUCKET}/{chunk_key}")

                s3_client.put_object(
                    Bucket=TEXT_CHUNKS_BUCKET,
                    Key=chunk_key,
                    Body=chunk_text.encode('utf-8'),
                    ContentType='text/plain; charset=utf-8',
                    Metadata={
                        'job_id': job_id,
                        'chunk_index': str(idx),
                        'total_chunks': str(len(chunks)),
                        'chunk_length': str(len(chunk_text))
                    }
                )

            # Save metadata
            metadata = {
                'job_id': job_id,
                'total_chunks': len(chunks),
                'chunk_size_tokens': MAX_TOKENS_PER_CHUNK,
                'tts_provider': TTS_PROVIDER,
                'source_text_key': text_s3_key,
                'total_characters': len(full_text),
                'estimated_words': len(full_text.split()),
                'created_at': datetime.now().isoformat(),
                'chunking_method': 'tiktoken' if TIKTOKEN_AVAILABLE else 'character-based'
            }

            metadata_key = f"{job_id}/metadata.json"
            print(f"Saving metadata to s3://{TEXT_CHUNKS_BUCKET}/{metadata_key}")

            s3_client.put_object(
                Bucket=TEXT_CHUNKS_BUCKET,
                Key=metadata_key,
                Body=json.dumps(metadata, indent=2),
                ContentType='application/json'
            )

            # Send each chunk to TTS queue
            print(f"Sending {len(chunks)} chunks to TTS queue: {TTS_QUEUE_URL}")
            for idx in range(len(chunks)):
                chunk_message = {
                    'job_id': job_id,
                    'chunk_index': idx,
                    'total_chunks': len(chunks),
                    'text_s3_bucket': TEXT_CHUNKS_BUCKET,
                    'text_s3_key': f"{job_id}/chunk_{idx:04d}.txt",
                    'source_file': message_body.get('source_file', 'unknown'),
                    'seed': seed,
                }
                if voice:
                    chunk_message['voice'] = voice

                sqs_client.send_message(
                    QueueUrl=TTS_QUEUE_URL,
                    MessageBody=json.dumps(chunk_message),
                    MessageGroupId=f"{job_id}-{idx % 2}",  # Split into 2 groups for parallel processing
                    MessageDeduplicationId=f"{job_id}-chunk-{idx:04d}"
                )

            update_job_status(job_id, 'GENERATING', progress=25,
                              total_chunks=len(chunks), completed_chunks=0)
            print(f"✓ Chunking complete for {job_id}: {len(chunks)} chunks sent to TTS queue")

            return {
                'statusCode': 200,
                'body': json.dumps({
                    'job_id': job_id,
                    'total_chunks': len(chunks),
                    'metadata_key': metadata_key
                })
            }

        except Exception as e:
            print(f"✗ Error processing {job_id}: {str(e)}")
            update_job_status(job_id, 'FAILED', error_message=str(e)[:500])
            import traceback
            traceback.print_exc()
            raise
