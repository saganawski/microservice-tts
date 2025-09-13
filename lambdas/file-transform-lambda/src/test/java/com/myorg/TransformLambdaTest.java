package com.myorg;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.myorg.mistral.MistralApiClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class TransformLambdaTest {

    static class TestableTransformLambda extends TransformLambda {
        Map<String, byte[]> uploaded = new HashMap<>();
        byte[] data;

        TestableTransformLambda(MistralApiClient client, byte[] data) {
            super(null, client);
            this.data = data;
        }

        @Override
        protected byte[] downloadFile(String bucketName, String key) {
            return data;
        }

        @Override
        protected void uploadChunk(String bucketName, String key, byte[] content) {
            uploaded.put(key, content);
        }
    }

    private Map<String, Object> buildEvent(String fileName) {
        Map<String, Object> object = new HashMap<>();
        object.put("key", fileName);
        Map<String, Object> s3 = new HashMap<>();
        s3.put("object", object);
        Map<String, Object> record = new HashMap<>();
        record.put("s3", s3);
        return Map.of("Records", List.of(record));
    }

    private Context createContext(StringBuilder logs) {
        return new Context() {
            @Override public String getAwsRequestId() { return "req"; }
            @Override public String getLogGroupName() { return "group"; }
            @Override public String getLogStreamName() { return "stream"; }
            @Override public String getFunctionName() { return "func"; }
            @Override public String getFunctionVersion() { return "1"; }
            @Override public String getInvokedFunctionArn() { return "arn"; }
            @Override public CognitoIdentity getIdentity() { return null; }
            @Override public ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() { return 0; }
            @Override public int getMemoryLimitInMB() { return 0; }
            @Override public LambdaLogger getLogger() { return logs::append; }
        };
    }

    @Test
    void parsesAndUploadsChunks() {
        MistralApiClient client = mock(MistralApiClient.class);
        when(client.parseToChunks("test content")).thenReturn(List.of("chunk1", "chunk2"));

        TestableTransformLambda lambda = new TestableTransformLambda(client, "test content".getBytes(StandardCharsets.UTF_8));

        StringBuilder logs = new StringBuilder();
        Context context = createContext(logs);

        Map<String, Object> result = lambda.handleRequest(buildEvent("file.txt"), context);

        assertEquals(200, result.get("statusCode"));
        assertEquals("File has been transformed successfully", result.get("body"));
        assertEquals(2, lambda.uploaded.size());
        assertTrue(lambda.uploaded.containsKey("file.txt-part-1"));
        assertTrue(lambda.uploaded.containsKey("file.txt-part-2"));
        String logOutput = logs.toString();
        assertTrue(logOutput.contains("Starting Mistral API call"));
        assertTrue(logOutput.contains("Completed Mistral API call"));
    }

    @Test
    void retriesAndLogsErrors() {
        MistralApiClient client = mock(MistralApiClient.class);
        when(client.parseToChunks(anyString())).thenThrow(new RuntimeException("network"));

        TestableTransformLambda lambda = new TestableTransformLambda(client, "data".getBytes(StandardCharsets.UTF_8));

        StringBuilder logs = new StringBuilder();
        Context context = createContext(logs);

        Map<String, Object> result = lambda.handleRequest(buildEvent("file.txt"), context);

        assertEquals(500, result.get("statusCode"));
        assertTrue(result.get("body").toString().startsWith("Error transforming file"));
        String logOutput = logs.toString();
        assertTrue(logOutput.contains("Starting Mistral API call"));
        assertTrue(logOutput.contains("Mistral API call failed"));
        assertTrue(lambda.uploaded.isEmpty());
    }
}

