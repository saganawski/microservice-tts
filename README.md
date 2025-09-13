# Microservice Text-to-Speech Pipeline

A serverless workflow for converting uploaded documents into audio using AWS services. The project is built with Java 21 and AWS CDK v2, and uses several Lambda functions to process files through validation, transformation, text-to-speech generation and notification steps.

## Table of Contents
- [Project Structure](#project-structure)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Testing](#testing)
- [Deployment](#deployment)
- [Contributing](#contributing)
- [License](#license)

## Project Structure
```
.
├── cdk/                      # Infrastructure as code written with AWS CDK
├── lambdas/
│   ├── file-validation-lambda/   # Validates file uploads before storing in S3
│   ├── file-transform-lambda/    # Parses files into chunks using Mistral (mocked)
│   ├── file-tts-lambda/          # Generates audio from text (placeholder)
│   └── notification-lambda/      # Sends notifications after processing
└── pom.xml                   # Maven multi-module configuration
```

## Features
- **File validation** – Verifies incoming uploads and stores valid files in the `ORIGINAL_BUCKET_NAME` S3 bucket, rejecting unsupported file types
- **File transformation** – Downloads validated files, sends them to the Mistral API (mocked) for parsing into chunks, and uploads each chunk to `CHUNK_BUCKET_NAME` for downstream text-to-speech processing
- **Text-to-speech generation** – Placeholder Lambda module designed to convert text chunks to audio files
- **Notifications** – Sends a simple notification after processing completes

## Tech Stack
- Java 21
- Maven for build and dependency management
- AWS Lambda & AWS SDK for Java v2
- Amazon S3 for temporary and chunk storage
- AWS CDK v2 for provisioning infrastructure
- JUnit 5 for unit tests

## Prerequisites
- **Java Development Kit (JDK) 21**
- **Apache Maven** 3.8+
- **Node.js** and **npm** for AWS CDK CLI
- **AWS CLI** with credentials configured
- An AWS account for deploying infrastructure

## Setup
1. Clone the repository
   ```bash
   git clone <repo-url>
   cd microservice-tts
   ```
2. Build all modules
   ```bash
   mvn package
   ```
3. (Optional) Set environment variables for local testing
   ```bash
   export ORIGINAL_BUCKET_NAME=<source-bucket>
   export CHUNK_BUCKET_NAME=<chunk-bucket>
   export MISTRAL_API_KEY=<mistral-api-key>
   ```

## Testing
Run the unit test suite:
```bash
mvn test
```
Example tests ensure the correct transformer is selected for supported file types

## Deployment
1. Install the AWS CDK CLI if not already installed:
   ```bash
   npm install -g aws-cdk
   ```
2. Synthesize the CloudFormation templates:
   ```bash
   cdk -a cdk synth
   ```
3. Deploy the stacks to your AWS account:
   ```bash
   cdk -a cdk deploy
   ```

## Contributing
Contributions are welcome! Please open an issue or submit a pull request for any improvements or bug fixes.

## License
This project is currently unlicensed. Please consult the project owner before using it in production.

