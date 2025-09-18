package com.myorg.stacks;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CDK Stack for VibeVoice TTS Service deployment on EC2
 */
public class VibeVoiceStack extends Stack {

    private final Instance vibeVoiceInstance;
    private final String serviceUrl;

    public VibeVoiceStack(final Construct scope, final String id, final StackProps props,
                          final Bucket markdownBucket, final Bucket processedBucket) {
        super(scope, id, props);

        // Create VPC for the VibeVoice service
        Vpc vpc = Vpc.Builder.create(this, "VibeVoiceVpc")
            .maxAzs(2)
            .natGateways(1)
            .subnetConfiguration(Arrays.asList(
                SubnetConfiguration.builder()
                    .name("Public")
                    .subnetType(SubnetType.PUBLIC)
                    .cidrMask(24)
                    .build(),
                SubnetConfiguration.builder()
                    .name("Private")
                    .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                    .cidrMask(24)
                    .build()
            ))
            .build();

        // Security group for VibeVoice service
        SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "VibeVoiceSecurityGroup")
            .vpc(vpc)
            .description("Security group for VibeVoice TTS service")
            .allowAllOutbound(true)
            .build();

        // Allow inbound traffic on port 8000 (VibeVoice service)
        securityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.tcp(8000),
            "Allow HTTP traffic to VibeVoice service"
        );

        // Allow SSH for management (restrict in production)
        securityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.tcp(22),
            "Allow SSH access"
        );

        // IAM role for EC2 instance
        Role instanceRole = Role.Builder.create(this, "VibeVoiceInstanceRole")
            .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
            ))
            .build();

        // Grant S3 access for potential model caching
        markdownBucket.grantRead(instanceRole);
        processedBucket.grantWrite(instanceRole);

        // User data script to install and run VibeVoice service
        UserData userData = UserData.forLinux();
        userData.addCommands(
            "#!/bin/bash",
            "set -e",
            "",
            "# Update system",
            "apt-get update",
            "apt-get upgrade -y",
            "",
            "# Install Docker",
            "curl -fsSL https://get.docker.com -o get-docker.sh",
            "sh get-docker.sh",
            "usermod -aG docker ubuntu",
            "",
            "# Install NVIDIA Container Toolkit",
            "distribution=$(. /etc/os-release;echo $ID$VERSION_ID)",
            "curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | apt-key add -",
            "curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | tee /etc/apt/sources.list.d/nvidia-docker.list",
            "apt-get update",
            "apt-get install -y nvidia-container-toolkit",
            "systemctl restart docker",
            "",
            "# Install Docker Compose",
            "curl -L \"https://github.com/docker/compose/releases/download/v2.20.0/docker-compose-$(uname -s)-$(uname -m)\" -o /usr/local/bin/docker-compose",
            "chmod +x /usr/local/bin/docker-compose",
            "",
            "# Create application directory",
            "mkdir -p /opt/vibevoice",
            "cd /opt/vibevoice",
            "",
            "# Create docker-compose.yml",
            "cat > docker-compose.yml << 'EOF'",
            "version: '3.8'",
            "services:",
            "  vibevoice:",
            "    image: vibevoice:latest",
            "    container_name: vibevoice-service",
            "    ports:",
            "      - '8000:8000'",
            "    environment:",
            "      - VIBEVOICE_MODEL=microsoft/VibeVoice-1.5B",
            "      - USE_QUANTIZATION=true",
            "      - PORT=8000",
            "    volumes:",
            "      - model-cache:/root/.cache/huggingface",
            "    deploy:",
            "      resources:",
            "        reservations:",
            "          devices:",
            "            - driver: nvidia",
            "              count: 1",
            "              capabilities: [gpu]",
            "    restart: unless-stopped",
            "volumes:",
            "  model-cache:",
            "EOF",
            "",
            "# Create Dockerfile",
            "cat > Dockerfile << 'EOF'",
            "FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime",
            "WORKDIR /app",
            "RUN apt-get update && apt-get install -y ffmpeg git curl && rm -rf /var/lib/apt/lists/*",
            "COPY requirements.txt .",
            "RUN pip install flask flask-cors torch transformers accelerate safetensors sentencepiece bitsandbytes scipy librosa soundfile gunicorn",
            "COPY server.py .",
            "COPY vibevoice_model.py .",
            "ENV PYTHONUNBUFFERED=1",
            "EXPOSE 8000",
            "CMD [\"python\", \"server.py\"]",
            "EOF",
            "",
            "# Note: In production, you would copy the actual Python files here",
            "# For now, create placeholder files",
            "echo 'print(\"VibeVoice service starting...\")' > server.py",
            "echo 'print(\"VibeVoice model module\")' > vibevoice_model.py",
            "touch requirements.txt",
            "",
            "# Build and run the service",
            "docker build -t vibevoice:latest .",
            "docker-compose up -d",
            "",
            "# Setup CloudWatch logging",
            "docker logs vibevoice-service"
        );

        // Select the appropriate instance type - g4dn.xlarge for 1.5B model
        InstanceType instanceType = InstanceType.of(InstanceClass.G4DN, InstanceSize.XLARGE);

        // Get the latest Deep Learning AMI with CUDA support
        IMachineImage machineImage = MachineImage.lookup(LookupMachineImageProps.builder()
            .name("Deep Learning AMI GPU PyTorch 2.* (Ubuntu 20.04)*")
            .owners(Arrays.asList("amazon"))
            .build());

        // Create EC2 instance
        vibeVoiceInstance = Instance.Builder.create(this, "VibeVoiceInstance")
            .vpc(vpc)
            .instanceType(instanceType)
            .machineImage(machineImage)
            .securityGroup(securityGroup)
            .role(instanceRole)
            .userData(userData)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PUBLIC)
                .build())
            .associatePublicIpAddress(true)
            .blockDevices(Arrays.asList(
                BlockDevice.builder()
                    .deviceName("/dev/sda1")
                    .volume(BlockDeviceVolume.ebs(100)) // 100 GB storage
                    .build()
            ))
            .build();

        // Create Elastic IP for stable endpoint
        CfnEIP elasticIp = CfnEIP.Builder.create(this, "VibeVoiceEIP")
            .domain("vpc")
            .instanceId(vibeVoiceInstance.getInstanceId())
            .build();

        // Store the service URL for Lambda configuration
        this.serviceUrl = "http://" + elasticIp.getRef() + ":8000";

        // Output the service URL
        new software.amazon.awscdk.CfnOutput(this, "VibeVoiceServiceUrl",
            software.amazon.awscdk.CfnOutputProps.builder()
                .value(serviceUrl)
                .description("URL for VibeVoice TTS service")
                .build());

        // Output instance ID for management
        new software.amazon.awscdk.CfnOutput(this, "VibeVoiceInstanceId",
            software.amazon.awscdk.CfnOutputProps.builder()
                .value(vibeVoiceInstance.getInstanceId())
                .description("EC2 Instance ID for VibeVoice service")
                .build());
    }

    /**
     * Get the VibeVoice service URL
     */
    public String getServiceUrl() {
        return serviceUrl;
    }

    /**
     * Get the VibeVoice EC2 instance
     */
    public Instance getVibeVoiceInstance() {
        return vibeVoiceInstance;
    }
}