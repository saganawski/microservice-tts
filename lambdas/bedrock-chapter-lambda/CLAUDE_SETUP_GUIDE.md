# Claude Sonnet 4.5 Setup Guide
## How to Use AWS Bedrock Claude on Any Computer

This guide walks you through running Claude Sonnet 4.5 on a different computer using the `test_claude.py` script.

---

## Prerequisites

Before you begin, ensure you have:

- **Python 3.8+** installed
- **Internet connection**
- **AWS Account** with Bedrock access

---

## Step 1: Install Required Software

### Install Python (if not already installed)

**macOS:**
```bash
brew install python3
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install python3 python3-pip
```

**Windows:**
Download from [python.org](https://www.python.org/downloads/)

### Verify Python Installation
```bash
python3 --version  # Should show Python 3.8+
pip3 --version     # Should show pip version
```

---

## Step 2: Set Up Python Virtual Environment (Recommended)

Using a virtual environment keeps your project dependencies isolated.

### Create Virtual Environment

```bash
# Navigate to project directory
cd /path/to/bedrock-chapter-lambda

# Create virtual environment
python3 -m venv venv

# Activate virtual environment
# On macOS/Linux:
source venv/bin/activate

# On Windows:
venv\Scripts\activate

# Your prompt should now show (venv)
```

### Install Dependencies

```bash
# Option 1: Install from requirements.txt (recommended)
pip install -r requirements.txt

# Option 2: Install manually
pip install boto3

# Verify installation
python -c "import boto3; print(boto3.__version__)"
```

### Deactivate Virtual Environment

When you're done working:
```bash
deactivate
```

### Requirements File

The `requirements.txt` file contains:
```
boto3>=1.34.0
botocore>=1.34.0
```

---

## Step 3: Set Up AWS Credentials

You need AWS credentials to access Bedrock. There are **3 methods** to configure credentials:

### Method A: AWS CLI (Recommended)

1. **Install AWS CLI:**
   ```bash
   pip3 install awscli
   ```

2. **Configure credentials:**
   ```bash
   aws configure
   ```

3. **Enter your credentials when prompted:**
   ```
   AWS Access Key ID [None]: AKIAIOSFODNN7EXAMPLE
   AWS Secret Access Key [None]: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
   Default region name [None]: us-east-1
   Default output format [None]: json
   ```

### Method B: Environment Variables

Set these in your terminal session:

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_DEFAULT_REGION=us-east-1
```

**Note:** These expire when you close the terminal.

### Method C: Credentials File (Best for Multiple Profiles)

1. **Create AWS config directory:**
   ```bash
   mkdir -p ~/.aws
   ```

2. **Create credentials file:**
   ```bash
   nano ~/.aws/credentials
   ```

3. **Add your credentials:**
   ```ini
   [default]
   aws_access_key_id = AKIAIOSFODNN7EXAMPLE
   aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
   ```

4. **Create config file:**
   ```bash
   nano ~/.aws/config
   ```

5. **Add region:**
   ```ini
   [default]
   region = us-east-1
   ```

---

## Managing Multiple AWS Profiles

If you work with multiple AWS accounts, you can store separate credential sets and easily switch between them.

### Setting Up Multiple Profiles

Edit `~/.aws/credentials`:
```ini
[default]
aws_access_key_id = YOUR_DEFAULT_KEY
aws_secret_access_key = YOUR_DEFAULT_SECRET

[personal]
aws_access_key_id = YOUR_PERSONAL_KEY
aws_secret_access_key = YOUR_PERSONAL_SECRET

[work]
aws_access_key_id = YOUR_WORK_KEY
aws_secret_access_key = YOUR_WORK_SECRET

[bedrock-dev]
aws_access_key_id = YOUR_BEDROCK_KEY
aws_secret_access_key = YOUR_BEDROCK_SECRET
```

Edit `~/.aws/config`:
```ini
[default]
region = us-east-1

[profile personal]
region = us-west-2

[profile work]
region = us-east-1

[profile bedrock-dev]
region = us-east-1
```

### Switching Between Profiles

**Method 1: Environment Variable**
```bash
# Use 'personal' profile
export AWS_PROFILE=personal
python3 test_claude.py

# Use 'work' profile
export AWS_PROFILE=work
python3 test_claude.py

# Back to default
unset AWS_PROFILE
```

**Method 2: AWS CLI Flag**
```bash
# Test which profile is active
aws sts get-caller-identity --profile personal

# Use specific profile with boto3 script
AWS_PROFILE=work python3 test_claude.py
```

**Method 3: In Python Code**

Modify `test_claude.py` to use specific profile:
```python
import boto3

# Use specific profile
session = boto3.Session(profile_name='bedrock-dev')
bedrock = session.client('bedrock-runtime', region_name='us-east-1')
```

### Profile Switcher Script

Create a helper script `switch_profile.sh`:

```bash
#!/bin/bash
# Save as: switch_profile.sh

case "$1" in
  personal)
    export AWS_PROFILE=personal
    echo "✅ Switched to personal profile"
    ;;
  work)
    export AWS_PROFILE=work
    echo "✅ Switched to work profile"
    ;;
  bedrock)
    export AWS_PROFILE=bedrock-dev
    echo "✅ Switched to bedrock-dev profile"
    ;;
  default)
    unset AWS_PROFILE
    echo "✅ Switched to default profile"
    ;;
  show)
    if [ -z "$AWS_PROFILE" ]; then
      echo "📍 Current profile: default"
    else
      echo "📍 Current profile: $AWS_PROFILE"
    fi
    aws sts get-caller-identity 2>/dev/null || echo "❌ No valid credentials"
    ;;
  *)
    echo "Usage: source switch_profile.sh {personal|work|bedrock|default|show}"
    ;;
esac
```

Make it executable and use it:
```bash
chmod +x switch_profile.sh

# Must use 'source' to affect current shell
source switch_profile.sh personal
source switch_profile.sh show
source switch_profile.sh default
```

### Backup Your Credentials

**Create a backup:**
```bash
# Backup credentials to encrypted archive
tar -czf aws-credentials-backup.tar.gz ~/.aws/
gpg -c aws-credentials-backup.tar.gz
rm aws-credentials-backup.tar.gz
```

**Restore from backup:**
```bash
# Decrypt and restore
gpg -d aws-credentials-backup.tar.gz.gpg > aws-credentials-backup.tar.gz
tar -xzf aws-credentials-backup.tar.gz -C ~/
```

---

## Step 4: Get Your AWS Credentials

You need to create AWS credentials with Bedrock permissions.

### Create IAM User with Bedrock Access

1. **Go to AWS Console:** https://console.aws.amazon.com/iam/
2. **Navigate to:** IAM → Users → Add users
3. **User name:** `bedrock-developer`
4. **Access type:** Select "Programmatic access"
5. **Click:** Next: Permissions

### Attach Bedrock Policy

**Option 1: Use AWS Managed Policy (Simple)**
- Attach policy: `AmazonBedrockFullAccess`

**Option 2: Create Custom Policy (Recommended)**

Create a custom policy with this JSON:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:ListFoundationModels",
        "bedrock:GetFoundationModel"
      ],
      "Resource": [
        "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:us-east-2::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:us-east-1:*:inference-profile/us.anthropic.claude-sonnet-4-5-20250929-v1:0"
      ]
    }
  ]
}
```

**Save the Access Key ID and Secret Access Key!** You won't be able to see the secret again.

---

## Step 5: Enable Claude Sonnet 4.5 in Bedrock

Before using Claude, you must enable model access:

1. **Go to:** https://console.aws.amazon.com/bedrock/
2. **Click:** "Model access" (left sidebar)
3. **Click:** "Modify model access"
4. **Find:** "Claude Sonnet 4.5" (Anthropic)
5. **Check the box** next to it
6. **Click:** "Save changes"
7. **Wait:** 1-2 minutes for access to be granted

---

## Step 6: Download the Test Script

### Option A: Copy from this repository

Copy the `test_claude.py` file to your computer.

### Option B: Create manually

Create a new file called `test_claude.py`:

```python
#!/usr/bin/env python3
"""
Test script for Claude Sonnet 4.5 via AWS Bedrock
"""

import boto3
import json

# Initialize Bedrock client
bedrock = boto3.client('bedrock-runtime', region_name='us-east-1')

def ask_claude(question: str, max_tokens: int = 500):
    """
    Send a question to Claude Sonnet 4.5

    Args:
        question: The question to ask
        max_tokens: Maximum tokens in response
    """
    # Prepare request
    request_body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": max_tokens,
        "messages": [
            {
                "role": "user",
                "content": question
            }
        ]
    }

    print(f"\n📤 Sending to Claude Sonnet 4.5...")
    print(f"Question: {question}\n")

    try:
        # Invoke model
        response = bedrock.invoke_model(
            modelId="us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            body=json.dumps(request_body)
        )

        # Parse response
        response_body = json.loads(response['body'].read())

        # Extract answer
        answer = response_body['content'][0]['text']
        usage = response_body['usage']

        print(f"📥 Response from Claude:")
        print(f"{answer}\n")
        print(f"📊 Token Usage:")
        print(f"  Input: {usage['input_tokens']} tokens")
        print(f"  Output: {usage['output_tokens']} tokens")
        print(f"  Model: {response_body['model']}")

        return answer

    except Exception as e:
        print(f"❌ Error: {e}")
        return None


if __name__ == "__main__":
    # Test with simple question
    ask_claude("What are the main themes in Bram Stoker's Dracula?")

    # Test with token counting
    print("\n" + "="*60)
    ask_claude("Count from 1 to 10", max_tokens=50)

    # Test with longer text analysis
    print("\n" + "="*60)
    sample_text = """
    Jonathan Harker travels to Transylvania to meet Count Dracula.
    The Count is a vampire who moves to England to spread the curse.
    """
    ask_claude(f"Summarize this in one sentence: {sample_text}", max_tokens=100)
```

Make it executable:
```bash
chmod +x test_claude.py
```

---

## Step 7: Run the Hello World Test

```bash
python3 test_claude.py
```

### Expected Output

```
📤 Sending to Claude Sonnet 4.5...
Question: What are the main themes in Bram Stoker's Dracula?

📥 Response from Claude:
Bram Stoker's "Dracula" explores several major themes including:

1. Good vs. Evil - The central conflict between vampire hunters and Count Dracula
2. Victorian Sexual Anxiety - Repressed desires and fear of female sexuality
3. Fear of the Foreign/Other - Dracula represents the threatening outsider
4. Science vs. Superstition - Modern medicine confronting ancient evil
5. Invasion and Contamination - Fear of corruption spreading through society

📊 Token Usage:
  Input: 15 tokens
  Output: 94 tokens
  Model: claude-sonnet-4-5-20250929

============================================================

📤 Sending to Claude Sonnet 4.5...
Question: Count from 1 to 10

📥 Response from Claude:
1, 2, 3, 4, 5, 6, 7, 8, 9, 10

📊 Token Usage:
  Input: 10 tokens
  Output: 14 tokens
  Model: claude-sonnet-4-5-20250929
```

---

## Troubleshooting

### Error: "No module named 'boto3'"

**Fix:**
```bash
pip3 install boto3
```

### Error: "Unable to locate credentials"

**Fix:** Your AWS credentials aren't configured. Go back to **Step 3**.

Verify credentials are set:
```bash
aws sts get-caller-identity
```

Should show your AWS account info.

### Error: "AccessDeniedException"

**Cause:** Your IAM user doesn't have Bedrock permissions.

**Fix:**
1. Go to IAM Console
2. Find your user
3. Attach `AmazonBedrockFullAccess` policy

### Error: "You don't have access to the model"

**Cause:** Claude Sonnet 4.5 not enabled in Bedrock.

**Fix:** Go to **Step 5** and enable model access.

### Error: "ValidationException: Input is too long"

**Cause:** Your prompt + response exceeds Claude's 200K token limit.

**Fix:** Reduce input size or use Claude Opus 4 (larger context window).

---

## Customizing Your Questions

Edit the `test_claude.py` script:

```python
if __name__ == "__main__":
    # Ask your own questions
    ask_claude("Explain quantum computing in simple terms", max_tokens=300)

    # Analyze text
    my_text = "Your long text here..."
    ask_claude(f"Summarize this: {my_text}", max_tokens=200)

    # Creative writing
    ask_claude("Write a haiku about autumn", max_tokens=100)
```

---

## Cost Information

**Claude Sonnet 4.5 Pricing (as of 2025):**
- **Input:** $3.00 per million tokens
- **Output:** $15.00 per million tokens

**Example costs:**
- Simple question (50 tokens): ~$0.0002
- Medium analysis (1000 tokens): ~$0.003
- Large book chapter (10K tokens): ~$0.03

**Monitor costs:** https://console.aws.amazon.com/billing/

---

## Next Steps

Once this works, you can:

1. **Build conversational AI** - Add chat history to messages array
2. **Analyze documents** - Upload PDFs, extract text, send to Claude
3. **Create APIs** - Wrap this in Flask/FastAPI for web access
4. **Integrate with apps** - Add Claude to your Python projects

---

## Additional Resources

- **AWS Bedrock Docs:** https://docs.aws.amazon.com/bedrock/
- **Claude API Docs:** https://docs.anthropic.com/
- **boto3 Docs:** https://boto3.amazonaws.com/v1/documentation/api/latest/index.html
- **Python AWS Examples:** https://github.com/awsdocs/aws-doc-sdk-examples/tree/main/python

---

## Support

If you encounter issues:

1. Check CloudWatch logs in AWS Console
2. Verify IAM permissions
3. Ensure model access is enabled
4. Check your AWS region is `us-east-1`

**Account ID:** 272765753210
**Model ID:** `us.anthropic.claude-sonnet-4-5-20250929-v1:0`
**Region:** us-east-1

---

**Last Updated:** October 8, 2025
**Claude Model:** Sonnet 4.5 (2025-09-29)
**Python Version:** 3.8+
