# TTS Microservice Architecture & Flow

## Complete System Overview

```mermaid
graph TB
    subgraph "Input Sources"
        API[REST API<br/>API Gateway]
        S3U[S3 Direct Upload]
    end

    subgraph "File Processing Pipeline"
        VL[ValidationLambda<br/>Java]
        OFB[(OriginalFileBucket<br/>S3)]
        TL[TransformLambda<br/>Java]
        MFB[(MarkdownFileBucket<br/>S3)]
        TTSL[TTSLambda<br/>Java - Provider Agnostic]
        PFB[(ProcessedFileBucket<br/>S3)]
    end

    subgraph "TTS Providers"
        subgraph "Option A: OpenAI"
            OAI[OpenAI API<br/>External Service]
        end

        subgraph "Option B: VibeVoice"
            EC2[EC2 Instance<br/>g4dn.xlarge]
            VV[VibeVoice 1.5B<br/>Docker Container]
            GPU[NVIDIA T4<br/>16GB VRAM]
        end
    end

    subgraph "Configuration"
        ENV[Environment Variables<br/>TTS_PROVIDER]
    end

    API -->|POST /file-upload| VL
    S3U -->|Direct Upload| OFB
    VL -->|Validate PDF/TXT| OFB
    OFB -->|S3 Event| TL
    TL -->|Mistral OCR<br/>Chunk to 4096 chars| MFB
    MFB -->|S3 Event| TTSL

    ENV -.->|Controls Provider| TTSL

    TTSL -->|If OPENAI| OAI
    TTSL -->|If VIBEVOICE| EC2
    EC2 --> VV
    VV --> GPU

    OAI -->|MP3/WAV Audio| TTSL
    GPU -->|WAV Audio| TTSL

    TTSL -->|Concatenated Audio| PFB

    style OAI fill:#e1f5fe
    style VV fill:#fff3e0
    style GPU fill:#ffecb3
    style ENV fill:#f3e5f5
```

## Detailed Book Processing Flow

```mermaid
sequenceDiagram
    participant User
    participant API
    participant ValidationLambda
    participant S3_Original
    participant TransformLambda
    participant S3_Markdown
    participant TTSLambda
    participant Provider
    participant S3_Processed

    User->>API: Upload Book (PDF/TXT)
    API->>ValidationLambda: Trigger validation
    ValidationLambda->>ValidationLambda: Check file type & size
    ValidationLambda->>S3_Original: Store original file

    S3_Original->>TransformLambda: S3 Event trigger
    TransformLambda->>TransformLambda: Extract text from PDF
    TransformLambda->>TransformLambda: Process with Mistral OCR
    TransformLambda->>TransformLambda: Chunk into sections
    TransformLambda->>S3_Markdown: Store markdown files

    S3_Markdown->>TTSLambda: S3 Event trigger

    alt OpenAI Provider
        TTSLambda->>TTSLambda: Chunk to 900 chars
        loop For each chunk
            TTSLambda->>Provider: Call OpenAI TTS API
            Provider-->>TTSLambda: Return MP3 audio
        end
    else VibeVoice Provider
        TTSLambda->>TTSLambda: Chunk to 20,000 chars
        TTSLambda->>Provider: Call VibeVoice EC2
        Provider->>Provider: Process with GPU
        Provider-->>TTSLambda: Return WAV audio
    end

    TTSLambda->>TTSLambda: Concatenate audio chunks
    TTSLambda->>TTSLambda: Apply crossfading
    TTSLambda->>S3_Processed: Store final audiobook

    S3_Processed-->>User: Audiobook ready
```

## Provider Selection Logic

```mermaid
flowchart TD
    Start([Lambda Receives Text])
    CheckEnv{Check TTS_PROVIDER<br/>Environment Variable}

    CheckEnv -->|OPENAI| OpenAI[OpenAI Provider]
    CheckEnv -->|VIBEVOICE| VibeVoice[VibeVoice Provider]
    CheckEnv -->|Not Set| Default[Default to OpenAI]

    OpenAI --> ChunkSmall[Chunk to 900 chars]
    VibeVoice --> ChunkLarge[Chunk to 20,000 chars]
    Default --> ChunkSmall

    ChunkSmall --> CallOpenAI[Call OpenAI API]
    ChunkLarge --> CheckEC2{EC2 Running?}

    CheckEC2 -->|No| StartEC2[Start EC2 Instance]
    CheckEC2 -->|Yes| CallVibe[Call VibeVoice]
    StartEC2 --> Wait[Wait 30 seconds]
    Wait --> CallVibe

    CallOpenAI --> ProcessAudio[Process Audio]
    CallVibe --> ProcessAudio

    ProcessAudio --> Concatenate[Concatenate Chunks]
    Concatenate --> SaveS3[Save to S3]
    SaveS3 --> End([Complete])
```

## Infrastructure Components

```mermaid
graph LR
    subgraph "AWS Account"
        subgraph "Compute"
            L1[ValidationLambda<br/>5 min timeout]
            L2[TransformLambda<br/>5 min timeout]
            L3[TTSLambda<br/>5 min timeout]
            EC2I[EC2 Instance<br/>g4dn.xlarge<br/>Optional]
        end

        subgraph "Storage"
            S1[(OriginalFileBucket)]
            S2[(MarkdownFileBucket)]
            S3[(ProcessedFileBucket)]
            EBS[EBS Volume<br/>100GB]
        end

        subgraph "Networking"
            VPC[VPC<br/>Public + Private Subnets]
            EIP[Elastic IP]
            SG[Security Group<br/>Port 8000]
            IGW[Internet Gateway]
        end

        subgraph "API"
            APIG[API Gateway]
            CF[CloudFormation<br/>3 Stacks]
        end
    end

    subgraph "External Services"
        OPENAI[OpenAI TTS API]
        HF[HuggingFace<br/>Model Repo]
    end

    L3 -.->|API Calls| OPENAI
    EC2I -.->|Download Model| HF
    EC2I --- EBS
    EC2I --- EIP
    EC2I --- SG
    VPC --- IGW
```

## Cost Optimization Flow

```mermaid
stateDiagram-v2
    [*] --> Deployed: Deploy Infrastructure

    Deployed --> OpenAI_Mode: Default State

    OpenAI_Mode --> EC2_Stopped: EC2 Exists but Stopped
    note right of EC2_Stopped: Cost: $8/month<br/>(EBS storage only)

    EC2_Stopped --> EC2_Running: switch-to-vibevoice
    note right of EC2_Running: Cost: $0.526/hour<br/>($378/month if 24/7)

    EC2_Running --> EC2_Stopped: switch-to-openai

    EC2_Running --> Processing: Process Books
    Processing --> EC2_Running: Continue

    EC2_Stopped --> Destroyed: vibevoice-destroy
    note right of Destroyed: Cost: $0

    Destroyed --> [*]
```

## Book Processing Example

```mermaid
graph TD
    subgraph "300-Page Book Processing"
        Book[Book.pdf<br/>300 pages]

        Book --> Extract[Text Extraction<br/>~500,000 chars]

        Extract --> ProviderChoice{Provider?}

        ProviderChoice -->|OpenAI| OAChunks[556 chunks<br/>@ 900 chars]
        ProviderChoice -->|VibeVoice| VVChunks[25 chunks<br/>@ 20,000 chars]

        OAChunks --> OAProcess[Process Time:<br/>~10 minutes]
        VVChunks --> VVProcess[Process Time:<br/>~5 minutes]

        OAProcess --> OACost[Cost: ~$7.50]
        VVProcess --> VVCost[Cost: ~$0.04<br/>5 min EC2 time]

        OACost --> Audio1[Audiobook.wav<br/>~10 hours]
        VVCost --> Audio2[Audiobook.wav<br/>~10 hours]
    end
```

## Development Workflow

```mermaid
gitGraph
    commit id: "Initial Setup"
    commit id: "Deploy with OpenAI"

    branch vibevoice-test
    checkout vibevoice-test
    commit id: "Deploy VibeVoice EC2"
    commit id: "Test VibeVoice"
    commit id: "Stop EC2"

    checkout main
    merge vibevoice-test
    commit id: "Continue with OpenAI"

    branch production
    checkout production
    commit id: "Deploy VibeVoice Prod"
    commit id: "Switch to VibeVoice"
```

## Error Handling Flow

```mermaid
flowchart TD
    Start([TTS Processing])

    Try[Try TTS Provider]

    Try --> Success{Success?}

    Success -->|Yes| Save[Save Audio]
    Success -->|No| CheckProvider{Current Provider?}

    CheckProvider -->|VibeVoice| CheckEC2{EC2 Healthy?}
    CheckProvider -->|OpenAI| CheckAPI{API Key Valid?}

    CheckEC2 -->|No| RestartEC2[Restart EC2]
    CheckEC2 -->|Yes| Fallback[Fallback to OpenAI]

    CheckAPI -->|No| Error1[Configuration Error]
    CheckAPI -->|Yes| Retry[Retry with backoff]

    RestartEC2 --> RetryVibe[Retry VibeVoice]
    RetryVibe --> Success

    Fallback --> Success
    Retry --> Success

    Save --> End([Complete])
    Error1 --> EndError([Failed])
```

## Data Flow Sizes

```mermaid
graph LR
    subgraph "Data Transformation"
        PDF[PDF Book<br/>10MB]
        PDF -->|Extract| Text[Raw Text<br/>500KB]
        Text -->|Process| Markdown[Markdown<br/>450KB]

        Markdown -->|OpenAI| Chunks1[556 chunks<br/>@ 900 chars]
        Markdown -->|VibeVoice| Chunks2[25 chunks<br/>@ 20,000 chars]

        Chunks1 -->|TTS| Audio1[WAV Files<br/>556 × 100KB]
        Chunks2 -->|TTS| Audio2[WAV Files<br/>25 × 2MB]

        Audio1 -->|Concat| Final1[Final WAV<br/>~500MB]
        Audio2 -->|Concat| Final2[Final WAV<br/>~500MB]
    end
```

## Deployment Commands Map

```mermaid
mindmap
  root((Deployment))
    Development
      ./deploy-vibevoice.sh development
      Uses OpenAI
      No EC2 deployed

    VibeVoice Testing
      Local Testing
        ./deploy-vibevoice.sh vibevoice-local
        Requires 6GB+ VRAM

      EC2 Deployment
        ./deploy-vibevoice.sh vibevoice-deploy
        Creates EC2 instance

    Provider Switching
      To OpenAI
        ./deploy-vibevoice.sh switch-to-openai
        Stops EC2

      To VibeVoice
        ./deploy-vibevoice.sh switch-to-vibevoice
        Starts EC2

    Cost Management
      Status Check
        ./deploy-vibevoice.sh vibevoice-status

      Stop EC2
        ./deploy-vibevoice.sh vibevoice-stop

      Destroy All
        ./deploy-vibevoice.sh vibevoice-destroy
```

## Summary

This architecture provides:
- 📚 **Scalable book processing** from PDF/TXT to audiobook
- 🔄 **Provider flexibility** between OpenAI and VibeVoice
- 💰 **Cost optimization** with start/stop EC2 capability
- 🚀 **Performance options** based on requirements
- 🛡️ **Safe defaults** preventing accidental charges

The system automatically handles the complete flow from book upload to audiobook generation, with provider selection controlled by simple environment variables.