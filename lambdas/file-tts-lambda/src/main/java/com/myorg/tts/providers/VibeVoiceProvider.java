package com.myorg.tts.providers;

import com.myorg.tts.TtsConfig;
import com.myorg.tts.TtsProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * VibeVoice TTS provider implementation
 * Supports Microsoft VibeVoice 1.5B model for development
 * Can be upgraded to 7B model for production
 */
public class VibeVoiceProvider implements TtsProvider {
    private final String endpointUrl;
    private final HttpClient httpClient;

    // VibeVoice specific configurations
    private static final int MAX_CHUNK_SIZE = 20000; // VibeVoice can handle much larger chunks
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2); // Longer timeout for larger chunks

    public VibeVoiceProvider(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public byte[] convertTextToSpeech(String text, TtsConfig config) throws IOException, InterruptedException {
        validateConfiguration();

        // Build VibeVoice-specific request body
        String requestBody = buildVibeVoiceRequest(text, config);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl + "/tts"))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("VibeVoice TTS API request failed: " + response.statusCode());
        }

        // VibeVoice returns WAV format directly
        return response.body();
    }

    @Override
    public String getProviderName() {
        return "VibeVoice";
    }

    @Override
    public void validateConfiguration() throws IllegalStateException {
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IllegalStateException("VibeVoice endpoint URL is not configured");
        }
    }

    /**
     * Build request body specific to VibeVoice API
     */
    private String buildVibeVoiceRequest(String text, TtsConfig config) {
        // VibeVoice specific parameters
        String speakerName = config.getVoice() != null ? config.getVoice() : "Speaker0";
        double temperature = 0.7; // Default temperature for natural speech
        int cfgScale = 3; // Classifier-free guidance scale
        int inferenceSteps = 50; // Diffusion steps

        return String.format("""
            {
                "text": "%s",
                "speaker_names": ["%s"],
                "temperature": %.1f,
                "cfg_scale": %d,
                "inference_steps": %d,
                "max_length": %d,
                "format": "wav"
            }
            """,
            escapeJson(text),
            speakerName,
            temperature,
            cfgScale,
            inferenceSteps,
            MAX_CHUNK_SIZE
        );
    }

    /**
     * Get the maximum chunk size for this provider
     */
    public static int getMaxChunkSize() {
        return MAX_CHUNK_SIZE;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}