package com.myorg;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.myorg.fileTransformation.creator.SimpleFileTransformFactory;
import com.myorg.fileTransformation.product.TransformFile;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

public class TransformLambda implements RequestHandler<Map<String, Object>, String> {
        private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
        private static final String CHUNK_BUCKET_NAME = System.getenv("CHUNK_BUCKET_NAME");
        private static final int TOKEN_SIZE = 4096;

        private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();

        @Override
        public String handleRequest(Map<String, Object> event, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Initiating Transform lambda function");
            context.getLogger().log("Received event: " + event);

            // get the filename from the event
            final List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");

            final String fileName = records.stream()
                .map(record -> (Map<String, Object>) record.get("s3"))
                .map(s3 -> (Map<String, Object>) s3.get("object"))
                .map(object -> (String) object.get("key"))
                .findFirst()
                .orElse(null);

            if(fileName == null) {
                context.getLogger().log("Invalid request: No file name found.");
                return "Invalid request: No file name found.";
            }

            context.getLogger().log("File name: " + fileName);

            // download fhe file from original file bucket
            final GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(ORIGINAL_BUCKET_NAME)
                    .key(fileName)
                    .build();

            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final GetObjectResponse s3Object = s3Client.getObject(objectRequest, ResponseTransformer.toOutputStream(outputStream));

            final boolean successful = s3Object.sdkHttpResponse().isSuccessful();
            context.getLogger().log("Download successful: " + successful);

            byte[] fileContent = outputStream.toByteArray();

            // transform the file
            final TransformFile transformFile = new SimpleFileTransformFactory().createTransformFile(fileName);
            final String transformFileContent = transformFile.transformFileContent(fileContent);
            final List<Map<String, byte[]>> fileContentToTokenSize = transformFile.transformFileContentToTokenSize(transformFileContent, TOKEN_SIZE, fileName);

            fileContentToTokenSize.stream()
                .filter(chunk -> chunk.keySet().stream().findFirst().isPresent())
                .forEach(chunk -> {
                    final String chunkFileName = chunk.keySet().stream().findFirst().get();
                    final byte[] chunkContent = chunk.values().stream().findFirst().get();

                    final PutObjectResponse putObjectResponse = transformFile.uploadFileToS3(CHUNK_BUCKET_NAME, chunkFileName, chunkContent, s3Client);
                    context.getLogger().log(chunkFileName +" : Successful upload ?: " + putObjectResponse.sdkHttpResponse().isSuccessful());
                    //TODO: handle the case where the upload is not successful
                });

            // TODO: return a more meaningful response
            return "File has been transformed successfully";
        }

}
