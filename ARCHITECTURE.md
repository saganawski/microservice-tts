# Architecture

## Processing Pipeline

```mermaid
flowchart TD
    Client([Client / Browser]) -->|POST /file-upload| APIGW[API Gateway<br/>FileProcessingApi]
    Client -->|POST /generate-upload-url| APIGW

    APIGW -->|small files| ValLambda[FileValidationLambda<br/>Java 21]
    APIGW -->|large files| PresignedLambda[PresignedUrlLambda<br/>Java 21]
    PresignedLambda -->|metadata| OrigBucket

    ValLambda -->|store validated file| OrigBucket[(OriginalFileBucket)]

    OrigBucket -->|S3 event .pdf| TextractLambda[TextractExtractionLambda<br/>Python 3.12]
    TextractLambda -->|raw JSON + text| TextractBucket[(TextractResultsBucket)]
    TextractLambda -->|SQS message| ChunkingQ[/ChunkingQueue.fifo/]

    ChunkingQ --> ChunkLambda[TextChunkingLambda<br/>Python 3.12]
    ChunkLambda -->|read full text| TextractBucket
    ChunkLambda -->|4500-token chunks| ChunksBucket[(TextChunksBucket)]
    ChunkLambda -->|SQS messages| TTSQ[/TTSQueue.fifo/]

    TTSQ --> TTSLambda[TTSGenerationLambda<br/>Python 3.12<br/>concurrency: 2]
    TTSLambda -->|read chunk| ChunksBucket
    TTSLambda -->|WAV audio| AudioBucket[(AudioChunksBucket)]
    TTSLambda -->|SQS message| StitchQ[/StitchQueue.fifo/]
    TTSLambda -.->|validation failures| ValAlertSNS([ValidationAlertTopic])

    StitchQ --> StitchLambda[AudioStitchingLambda<br/>Python 3.12<br/>3GB RAM, 4GB /tmp]
    StitchLambda -->|read chunks| AudioBucket
    StitchLambda -->|track progress| DDB[(JobAudioChunkTracking<br/>DynamoDB)]
    StitchLambda -->|final audio| ProcessedBucket[(ProcessedFileBucket)]
    StitchLambda -->|cleanup| AudioBucket
    StitchLambda -->|SNS message| JobSNS([JobCompletionTopic])

    JobSNS --> NotifLambda[NotificationLambda<br/>Python 3.12]
    NotifLambda -->|presigned URLs| ProcessedBucket
    NotifLambda -->|email with links| Email([Email Notification])

    TTSQ -.->|failed messages| TTSDLQ[/tts-dlq.fifo/]
    ChunkingQ -.->|failed messages| ChunkDLQ[/chunking-dlq.fifo/]

    subgraph Audit System
        AuditQ[/audit-queue.fifo/] --> AuditLambda[AuditLambda<br/>Python 3.12]
        AuditLambda -->|read text| ChunksBucket
        AuditLambda -->|read audio| AudioBucket
        AuditLambda -->|WER reports| AuditBucket[(AuditResultsBucket)]
        AuditLambda -->|summary| AuditSNS([AuditNotificationTopic])
    end

    style ValLambda fill:#4a9eff
    style PresignedLambda fill:#4a9eff
    style TextractLambda fill:#2ecc71
    style ChunkLambda fill:#2ecc71
    style TTSLambda fill:#2ecc71
    style StitchLambda fill:#2ecc71
    style NotifLambda fill:#2ecc71
    style AuditLambda fill:#2ecc71
```

**Legend**: Blue = Java Lambda, Green = Python Lambda

## Resource Inventory

### S3 Buckets

| Bucket | Purpose | Lifecycle |
|--------|---------|-----------|
| `original-file-bucket` | Uploaded PDF/TXT/EPUB files | None |
| `textract-results-bucket` | Raw Textract JSON + extracted text | 30 days |
| `text-chunks-bucket` | 4500-token text chunks | 7 days (auto-delete) |
| `audio-chunks-bucket` | Individual WAV audio chunks | 7 days |
| `processed-file-bucket` | Final stitched audio files | None |
| `audit-results-bucket` | WER reports and transcriptions | 90 days |

### Lambda Functions

| Lambda | Runtime | Trigger | Memory | Timeout | Concurrency |
|--------|---------|---------|--------|---------|-------------|
| FileValidationLambda | Java 21 | API Gateway POST | 1024 MB | 15 min | Default |
| PresignedUrlLambda | Java 21 | API Gateway POST | 512 MB | 30 sec | Default |
| TextractExtractionLambda | Python 3.12 | S3 event (.pdf) | 2048 MB | 15 min | Default |
| TextChunkingLambda | Python 3.12 | SQS (ChunkingQueue) | 1024 MB | 2 min | Default |
| TTSGenerationLambda | Python 3.12 | SQS (TTSQueue) | 2048 MB | 15 min | 2 |
| AudioStitchingLambda | Python 3.12 | SQS (StitchQueue) | 3008 MB | 10 min | 5 |
| NotificationLambda | Python 3.12 | SNS (JobCompletion) | 256 MB | 1 min | Default |
| AuditLambda | Python 3.12 | SQS (AuditQueue) | 1024 MB | 15 min | Default |

### SQS Queues

| Queue | Type | Visibility Timeout | DLQ | Purpose |
|-------|------|-------------------|-----|---------|
| `chunking-queue.fifo` | FIFO | 5 min | chunking-dlq | Text chunking jobs |
| `chunking-dlq.fifo` | FIFO | - | - | Failed chunking jobs |
| `tts-processing-queue.fifo` | FIFO | 16 min | tts-dlq | TTS generation jobs |
| `tts-dlq.fifo` | FIFO | - | - | Failed TTS jobs |
| `stitch-notification-queue.fifo` | FIFO | 11 min | - | Audio stitching triggers |
| `audit-queue.fifo` | FIFO | 16 min | - | Audit requests |

### SNS Topics

| Topic | Purpose | Subscribers |
|-------|---------|-------------|
| `tts-validation-alerts` | Audio validation failures after retries | Email |
| `tts-job-complete` | Job completion notifications | NotificationLambda + Email |
| `tts-audit-complete` | Audit result summaries | Email |

### DynamoDB Tables

| Table | Partition Key | Billing | TTL | Purpose |
|-------|--------------|---------|-----|---------|
| `JobAudioChunkTracking` | `job_id` (String) | On-demand | Yes | Track chunk completion per job |

### API Gateway

| API | Endpoint | Method | Handler |
|-----|----------|--------|---------|
| FileProcessingApi | `/file-upload` | POST | FileValidationLambda |
| FileProcessingApi | `/generate-upload-url` | POST | PresignedUrlLambda |
