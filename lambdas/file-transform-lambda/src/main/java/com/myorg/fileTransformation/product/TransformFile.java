package com.myorg.fileTransformation.product;

import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.List;
import java.util.Map;

public abstract class TransformFile {
    public abstract String transformFileContent(byte[] fileContent);
    public abstract List<Map<String, byte[]>> transformFileContentToTokenSize(String fileContent, int tokenSize, String fileName);

        public PutObjectResponse uploadFileToS3(String bucketName, String fileName, byte[] fileContent) {
            //TODO: Implement this method
        return null;
    }
}
