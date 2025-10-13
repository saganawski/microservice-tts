# Textract Processor Lambda

## Purpose
Retrieves completed AWS Textract job results, consolidates text from all pages, and uploads markdown to MarkdownFileBucket.

## Trigger
SNS notification from TextractCompletionTopic (sent by Textract when job completes)

## Process Flow
1. Receives SNS notification with Textract job completion
2. Validates job status (SUCCEEDED/FAILED)
3. Retrieves all pages from Textract using `GetDocumentTextDetection`
4. Extracts text blocks with layout preservation
5. Consolidates all pages into single markdown document
6. Detects potential headers (all-caps, short lines) and formats as H1
7. Uploads consolidated markdown to MarkdownFileBucket
8. Triggers BedrockChapterLambda via S3 event

## Environment Variables
- `MARKDOWN_BUCKET_NAME`: S3 bucket for markdown output

## IAM Permissions Required
- `textract:GetDocumentTextDetection`
- `s3:PutObject` on MarkdownFileBucket

## Input (SNS Message)
```json
{
  "JobId": "abc123...",
  "Status": "SUCCEEDED",
  "JobTag": "dracula.pdf",
  "DocumentLocation": {...}
}
```

## Output
- Single consolidated markdown file in MarkdownFileBucket
- Format: `{source-filename}.md`
- Triggers S3 event → BedrockChapterLambda

## Features
- Handles paginated Textract results (large documents)
- Basic header detection (all-caps text → H1 markdown)
- Layout-aware text extraction
- Comprehensive logging for debugging

## Dependencies
- boto3 (AWS SDK)

## Testing
```bash
python handler.py
```

## Notes
- Processes documents with up to 3,000 pages
- Uses pagination to handle large result sets
- Preserves line breaks and basic document structure
