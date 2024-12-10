package com.myorg.fileTransformation.product;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.List;
import java.util.Map;

public abstract class TransformFile {
    public abstract String transformFileContent(byte[] fileContent);
    public abstract List<Map<String, byte[]>> transformFileContentToTokenSize(String fileContent, int tokenSize, String fileName);

        public PutObjectResponse uploadFileToS3(String bucketName, String fileName, byte[] fileContent, S3Client s3Client) {

            final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .build();
            final PutObjectResponse putObjectResponse = s3Client.putObject(
                    putObjectRequest, RequestBody.fromBytes(fileContent));

            return putObjectResponse;
    }
}
