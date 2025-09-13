package com.myorg.mistral;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock client for the Mistral API. This implementation uses the provided API key
 * and splits the given text locally to simulate the API response.
 */
public class MistralApiClient {
    private static final int TOKEN_SIZE = 900;
    private final String apiKey;

    public MistralApiClient() {
        this(System.getenv("MISTRAL_API_KEY"));
    }

    public MistralApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> parseToChunks(String content) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Mistral API key is missing");
        }

        if (content == null || content.isEmpty()) {
            return List.of();
        }

        System.out.println("[" + Instant.now() + "] Starting mock Mistral parsing");
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + TOKEN_SIZE, content.length());
            chunks.add(content.substring(start, end));
            start = end;
        }
        System.out.println("[" + Instant.now() + "] Completed mock Mistral parsing");
        return chunks;
    }
}

