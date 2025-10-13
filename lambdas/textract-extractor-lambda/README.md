# Textract Extractor Lambda

## Purpose
Initiates asynchronous AWS Textract jobs for extracting text from PDF/EPUB files uploaded to the OriginalFileBucket.

## Trigger
S3 Event (OBJECT_CREATED) from `OriginalFileBucket`

## Process Flow
1. Receives S3 event when file is uploaded to OriginalFileBucket
2. Determines file type (PDF/EPUB/TXT)
3. Starts async Textract job with `StartDocumentTextDetection`
4. Configures SNS notification for job completion
5. Stores job metadata in S3 for tracking

## Environment Variables
- `ORIGINAL_BUCKET_NAME`: S3 bucket containing uploaded files
- `SNS_TOPIC_ARN`: SNS topic ARN for Textract completion notifications
- `TEXTRACT_ROLE_ARN`: IAM role ARN for Textract to publish to SNS

## IAM Permissions Required
- `s3:GetObject` on OriginalFileBucket
- `s3:PutObject` on OriginalFileBucket (for metadata)
- `textract:StartDocumentTextDetection`
- `sns:Publish` on TextractCompletionTopic

## Output
- Textract Job ID
- SNS notification sent to TextractProcessorLambda when job completes
- Job metadata stored in `textract-jobs/{jobId}.json`

## Dependencies
- boto3 (AWS SDK)
- PyMuPDF (for EPUB support - future enhancement)

## Testing
```bash
python handler.py
```

## Notes
- Uses LAYOUT feature for document hierarchy preservation
- Supports files up to 500 MB and 3,000 pages
- Async processing avoids Lambda timeout for large files
