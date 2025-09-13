package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.myorg.mistral.MistralApiClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransformLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
    private static final String CHUNK_BUCKET_NAME = System.getenv("CHUNK_BUCKET_NAME");

    private final S3Client s3Client;
    private final MistralApiClient mistralClient;

    public TransformLambda() {
        this(S3Client.builder().region(Region.US_EAST_1).build(), new MistralApiClient());
    }

    public TransformLambda(S3Client s3Client, MistralApiClient mistralClient) {
        this.s3Client = s3Client;
        this.mistralClient = mistralClient;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Initiating Transform lambda function");
        context.getLogger().log("Received event: " + event);

        final List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");
        final String fileName = records.stream()
                .map(record -> (Map<String, Object>) record.get("s3"))
                .map(s3 -> (Map<String, Object>) s3.get("object"))
                .map(object -> (String) object.get("key"))
                .findFirst()
                .orElse(null);

        if (fileName == null) {
            context.getLogger().log(timeStamp() + " Invalid request: No file name found.");
            return response(400, "Invalid request: No file name found.");
        }

        context.getLogger().log("File name: " + fileName);

        byte[] fileContent = downloadFile(ORIGINAL_BUCKET_NAME, fileName);
        String textContent = new String(fileContent, StandardCharsets.UTF_8);

        List<String> chunks;
        try {
            chunks = callMistralWithRetry(textContent, context);
        } catch (Exception e) {
            context.getLogger().log(timeStamp() + " Failed to parse file with Mistral API: " + e.getMessage());
            return response(500, "Error transforming file: " + e.getMessage());
        }

        int partNumber = 1;
        for (String chunk : chunks) {
            String chunkFileName = fileName + "-part-" + partNumber;
            uploadChunk(CHUNK_BUCKET_NAME, chunkFileName, chunk.getBytes(StandardCharsets.UTF_8));
            context.getLogger().log("Uploaded chunk: " + chunkFileName);
            partNumber++;
        }

        context.getLogger().log(timeStamp() + " Successfully transformed file");
        return response(200, "File has been transformed successfully");
    }

    protected byte[] downloadFile(String bucketName, String key) {
        final GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        s3Client.getObject(objectRequest, ResponseTransformer.toOutputStream(outputStream));
        return outputStream.toByteArray();
    }

    protected void uploadChunk(String bucketName, String key, byte[] content) {
        final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
    }

    private List<String> callMistralWithRetry(String content, Context context) {
        int attempts = 0;
        while (true) {
            try {
                context.getLogger().log(timeStamp() + " Starting Mistral API call");
                List<String> chunks = mistralClient.parseToChunks(content);
                context.getLogger().log(timeStamp() + " Completed Mistral API call");
                return chunks;
            } catch (Exception e) {
                attempts++;
                context.getLogger().log(timeStamp() + " Mistral API call failed: " + e.getMessage());
                if (attempts >= 3) {
                    throw e;
                }
            }
        }
    }

    private Map<String, Object> response(int statusCode, String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("statusCode", statusCode);
        resp.put("body", message);
        return resp;
    }

    private String timeStamp() {
        return "[" + Instant.now().toString() + "]";
    }
}

