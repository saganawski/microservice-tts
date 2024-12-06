package com.myorg;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class TransformLambda implements RequestHandler<Map<String, Object>, String> {
        private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
        private static final String CHUNK_BUCKET_NAME = System.getenv("CHUNK_BUCKET_NAME");

        private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();

        @Override
        public String handleRequest(Map<String, Object> event, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Transforming file");
            context.getLogger().log("Received event: " + event);
            context.getLogger().log("Context: " + context);

            // get the filename from the event
            final List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");

            final String fileName = records.stream()
                .map(record -> (Map<String, Object>) record.get("s3"))
                .map(s3 -> (Map<String, Object>) s3.get("object"))
                .map(object -> (String) object.get("key"))
                .findFirst()
                .orElse(null);

            context.getLogger().log("File name: " + fileName);

            // download fhe file from original file bucket
            final GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(ORIGINAL_BUCKET_NAME)
                    .key(fileName)
                    .build();

            /*final GetObjectResponse object = s3Client.getObject(objectRequest, ResponseTransformer.toFile(Paths.get("/tmp/" + fileName)));
            
            final boolean successful = object.sdkHttpResponse().isSuccessful();
            context.getLogger().log("Download successful: " + successful);*/

            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final GetObjectResponse s3Object = s3Client.getObject(objectRequest, ResponseTransformer.toOutputStream(outputStream));

            final boolean successful = s3Object.sdkHttpResponse().isSuccessful();
            context.getLogger().log("Download successful: " + successful);

            byte[] fileContent = outputStream.toByteArray();

            byte[] transformedContent = transformFileContent(fileContent);


            // split the file into 4096 character chunks labeled with a sequence number and filename
            // upload the chunks to the chunk file bucket
            // Transform the file
            return "File has been transformed";
        }

    private byte[] transformFileContent(byte[] fileContent) {
        // Transform the file content
        return fileContent;
    }
}

//The API input limit to TTS models is currently 4096 characters.
//https://platform.openai.com/docs/api-reference/audio