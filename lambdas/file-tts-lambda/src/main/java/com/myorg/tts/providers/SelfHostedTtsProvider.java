package com.myorg.tts.providers;

import com.myorg.tts.TtsConfig;
import com.myorg.tts.TtsProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Self-hosted TTS provider implementation
 * This can be used with various self-hosted TTS models like:
 * - Coqui TTS
 * - Mozilla TTS
 * - VITS
 * - Tacotron2
 * - FastSpeech
 */
public class SelfHostedTtsProvider implements TtsProvider {
    private final String endpointUrl;
    private final String apiKey; // Optional, some self-hosted solutions may require auth
    private final HttpClient httpClient;
    private final String authHeader; // Could be "Authorization", "X-API-Key", etc.

    public SelfHostedTtsProvider(String endpointUrl, String apiKey, String authHeader) {
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey;
        this.authHeader = authHeader != null ? authHeader : "Authorization";
        this.httpClient = HttpClient.newHttpClient();
    }

    public SelfHostedTtsProvider(String endpointUrl) {
        this(endpointUrl, null, null);
    }

    @Override
    public byte[] convertTextToSpeech(String text, TtsConfig config) throws IOException, InterruptedException {
        validateConfiguration();

        // Build request body based on common self-hosted TTS API patterns
        // This is a generic format that can be adapted based on the specific TTS service
        String requestBody = buildRequestBody(text, config);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json");

        // Add authentication if configured
        if (apiKey != null && !apiKey.isEmpty()) {
            if (authHeader.equalsIgnoreCase("Authorization")) {
                requestBuilder.header(authHeader, "Bearer " + apiKey);
            } else {
                requestBuilder.header(authHeader, apiKey);
            }
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new RuntimeException("Self-hosted TTS API request failed: " + response.statusCode() + " - " + errorBody);
        }

        return response.body();
    }

    @Override
    public String getProviderName() {
        return "SelfHosted";
    }

    @Override
    public void validateConfiguration() throws IllegalStateException {
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IllegalStateException("Self-hosted TTS endpoint URL is not configured");
        }
    }

    /**
     * Build request body for the self-hosted TTS service
     * This method should be customized based on the specific API requirements
     */
    private String buildRequestBody(String text, TtsConfig config) {
        // Generic JSON format that can work with many TTS services
        // Specific implementations might need to override this
        return String.format("""
                {
                    "text": "%s",
                    "voice": "%s",
                    "language": "%s",
                    "speed": %.1f,
                    "model": "%s",
                    "format": "mp3",
                    "sample_rate": 24000
                }
                """,
                text.replace("\"", "\\\"").replace("\n", "\\n"),
                config.getVoice(),
                config.getLanguage(),
                config.getSpeed(),
                config.getModel()
        );
    }

    /**
     * Alternative constructor for common self-hosted TTS services
     */
    public static SelfHostedTtsProvider forCoquiTts(String baseUrl) {
        // Coqui TTS typically runs on port 5002
        String endpoint = baseUrl + "/api/tts";
        return new SelfHostedTtsProvider(endpoint);
    }

    public static SelfHostedTtsProvider forCustomService(String endpointUrl, String apiKey) {
        return new SelfHostedTtsProvider(endpointUrl, apiKey, "X-API-Key");
    }
}