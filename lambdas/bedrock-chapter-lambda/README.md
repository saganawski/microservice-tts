# Bedrock Chapter Detection Lambda

## Purpose
Uses Amazon Bedrock (Claude Sonnet 4.5) to intelligently detect chapters, split books, and upload EACH chapter as individual file to trigger parallel TTS processing.

## Trigger
S3 Event (OBJECT_CREATED) from `MarkdownFileBucket`

## Process Flow
1. Downloads full book markdown from MarkdownFileBucket
2. Sends markdown to Bedrock Claude with structured prompt
3. Claude intelligently identifies chapter boundaries:
   - Handles variations: "Chapter 1", "Chapter One", "1.", "CHAPTER I", etc.
   - Removes front/back matter (TOC, copyright, index, appendices)
   - Understands semantic breaks (not just regex)
4. Splits book into individual chapter objects
5. **Uploads EACH chapter as separate .md file** to ChaptersBucket:
   - Format: `book-name/001.md`, `book-name/002.md`, etc.
   - Each upload triggers ONE GeminiTTS Lambda invocation
6. Creates metadata.json with chapter index

## Why Individual Chapter Files?
- ✅ **Avoids Lambda timeout**: One chapter = 5-10 min TTS (within 15-min limit)
- ✅ **Parallel processing**: 27 chapters = 27 simultaneous TTS lambdas
- ✅ **Fault tolerance**: If one chapter fails, others succeed
- ✅ **Cost efficiency**: Pay only for chapters processed

## Environment Variables
- `MARKDOWN_BUCKET_NAME`: S3 bucket for markdown input
- `CHAPTERS_BUCKET_NAME`: S3 bucket for chapter output
- `BEDROCK_MODEL_ID`: Bedrock model (default: `anthropic.claude-sonnet-4-5-20250929-v1:0`)
- `BEDROCK_REGION`: AWS region (default: `us-east-1`)

## IAM Permissions Required
- `s3:GetObject` on MarkdownFileBucket
- `s3:PutObject` on ChaptersBucket (multiple uploads)
- `bedrock:InvokeModel`

## Output Structure
```
ChaptersBucket/
└── dracula/
    ├── metadata.json         # Chapter index (does NOT trigger TTS)
    ├── 001.md               # Chapter 1 → triggers TTS Lambda #1
    ├── 002.md               # Chapter 2 → triggers TTS Lambda #2
    ├── 003.md               # Chapter 3 → triggers TTS Lambda #3
    └── ...                  # Each file triggers separate lambda
```

## Chapter Detection Intelligence
Claude understands:
- Numbered chapters: "Chapter 1", "1.", "I", "One"
- Named chapters: "Prologue", "Epilogue", "Introduction"
- Unnumbered sections with semantic breaks
- Different formatting styles

Claude excludes:
- Table of Contents
- Copyright/Legal pages
- Dedications, Acknowledgments
- Index, Glossary, Bibliography
- Appendices
- "About the Author"

## Example Output
For Dracula (27 chapters):
- 27 separate .md files uploaded
- 27 GeminiTTS Lambda invocations
- ~10 minutes total processing time (parallel)
- 27 .wav files in ProcessedFileBucket

## Dependencies
- boto3 (AWS SDK)
- prompts.py (chapter detection prompt templates)

## Testing
```bash
python handler.py
```

## Performance
- **Timeout**: 15 minutes (handles large books with 50+ chapters)
- **Token usage**: ~215K input tokens for Dracula (420 pages)
- **Cost**: ~$0.65 for Dracula (Sonnet 4.5)

## Notes
- Uses structured XML prompts for reliable parsing
- Low temperature (0.1) for consistent chapter detection
- Handles edge cases (no chapters, single chapter, unnumbered chapters)
- Comprehensive logging for debugging
