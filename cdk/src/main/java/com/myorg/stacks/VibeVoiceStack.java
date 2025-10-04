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

        // Grant S3 access if buckets are provided (optional for model caching)
        if (markdownBucket != null && processedBucket != null) {
            // This section is kept for backward compatibility but is no longer required
            // The TTS Lambda handles S3 access directly
        }

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

        // Grant S3 access if buckets are provided
        if (markdownBucket != null) {
            markdownBucket.grantRead(instanceRole);
        }
        if (processedBucket != null) {
            processedBucket.grantWrite(instanceRole);
        }

        // User data script for Deep Learning AMI (Ubuntu 22.04)
        // NVIDIA drivers, CUDA, Docker, and NVIDIA Container Toolkit are already installed
        UserData userData = UserData.forLinux();
        userData.addCommands(
            "#!/bin/bash",
            "set -e",
            "exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1",
            "",
            "echo 'Starting VibeVoice setup on Deep Learning AMI'",
            "",
            "# Disable GSP firmware for g4dn stability (required for g4dn instances)",
            "echo 'options nvidia NVreg_EnableGpuFirmware=0' | sudo tee /etc/modprobe.d/nvidia.conf",
            "",
            "# Add ubuntu user to docker group",
            "sudo usermod -aG docker ubuntu",
            "",
            "# Create application directory",
            "sudo mkdir -p /opt/vibevoice",
            "sudo chown -R ubuntu:ubuntu /opt/vibevoice",
            "",
            "# Create a marker file indicating user-data completed",
            "echo 'User data setup completed successfully' | sudo tee /opt/vibevoice/setup-complete.txt",
            "",
            "# Log GPU status",
            "nvidia-smi > /opt/vibevoice/gpu-status.txt 2>&1 || echo 'nvidia-smi failed' > /opt/vibevoice/gpu-status.txt",
            "",
            "echo 'User data script completed. Ready for VibeVoice service installation via SSM.'",
            "",
            "# Reboot to apply GSP firmware changes",
            "sudo reboot"
        );

        // Select the appropriate instance type - g4dn.xlarge for 1.5B model
        InstanceType instanceType = InstanceType.of(InstanceClass.G4DN, InstanceSize.XLARGE);

        // Use AWS Deep Learning AMI with pre-installed NVIDIA drivers
        // This AMI includes: NVIDIA Driver R570, CUDA 12.8, cuDNN 9.10, Docker, NVIDIA Container Toolkit
        IMachineImage machineImage = MachineImage.lookup(
            LookupMachineImageProps.builder()
                .name("Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04)*")
                .owners(Arrays.asList("amazon"))
                .build()
        );

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
                    .volume(BlockDeviceVolume.ebs(80,
                        EbsDeviceOptions.builder()
                            .volumeType(EbsDeviceVolumeType.GP3)
                            .deleteOnTermination(true)
                            .build()
                    ))
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