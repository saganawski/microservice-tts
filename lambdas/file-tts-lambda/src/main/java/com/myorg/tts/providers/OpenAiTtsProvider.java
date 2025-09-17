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
 * OpenAI TTS provider implementation
 */
public class OpenAiTtsProvider implements TtsProvider {
    private static final String OPENAI_TTS_ENDPOINT = "https://api.openai.com/v1/audio/speech";
    private final String apiKey;
    private final HttpClient httpClient;

    public OpenAiTtsProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public byte[] convertTextToSpeech(String text, TtsConfig config) throws IOException, InterruptedException {
        validateConfiguration();

        // Map generic config to OpenAI-specific parameters
        String openAiModel = mapModelName(config.getModel());
        String openAiVoice = mapVoiceName(config.getVoice());

        // Create request body with required fields
        StringBuilder requestBodyBuilder = new StringBuilder();
        requestBodyBuilder.append("{\n");

        // Required fields
        requestBodyBuilder.append(String.format("    \"model\": \"%s\",\n", openAiModel));
        requestBodyBuilder.append(String.format("    \"input\": \"%s\",\n", text.replace("\"", "\\\"").replace("\n", "\\n")));
        requestBodyBuilder.append(String.format("    \"voice\": \"%s\"", openAiVoice));

        // Optional fields
        // Speed (optional, only add if not default 1.0)
        if (Math.abs(config.getSpeed() - 1.0) > 0.001) {
            requestBodyBuilder.append(",\n");
            requestBodyBuilder.append(String.format("    \"speed\": %.1f", config.getSpeed()));
        }

        // Response format (optional, only add if not default mp3)
        if (config.getResponseFormat() != null && !config.getResponseFormat().equals("mp3")) {
            requestBodyBuilder.append(",\n");
            // OpenAI may interpret "wav" as "pcm", so let's be explicit
            String format = config.getResponseFormat();
            if (format.equals("wav")) {
                // Try pcm format instead, as OpenAI might return raw PCM when asked for WAV
                format = "pcm";
            }
            requestBodyBuilder.append(String.format("    \"response_format\": \"%s\"", format));
        }

        // Instructions (optional, only add if provided)
        if (config.getInstructions() != null && !config.getInstructions().isEmpty()) {
            requestBodyBuilder.append(",\n");
            requestBodyBuilder.append(String.format("    \"instructions\": \"%s\"",
                config.getInstructions().replace("\"", "\\\"").replace("\n", "\\n")));
        }

        requestBodyBuilder.append("\n}");
        String requestBody = requestBodyBuilder.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_TTS_ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new RuntimeException("OpenAI API request failed: " + response.statusCode() + " - " + errorBody);
        }

        return response.body();
    }

    @Override
    public String getProviderName() {
        return "OpenAI";
    }

    @Override
    public void validateConfiguration() throws IllegalStateException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key is not configured");
        }
    }

    /**
     * Map generic model names to OpenAI-specific model names
     */
    private String mapModelName(String genericModel) {
        return switch (genericModel) {
//            only use one model at this time
            case "standard", "default" -> "gpt-4o-mini-tts";
            case "hd", "high-quality" -> "gpt-4o-mini-tts";
            default -> "gpt-4o-mini-tts";
        };
    }

    /**
     * Map generic voice names to OpenAI-specific voice names
     */
    private String mapVoiceName(String genericVoice) {
        // OpenAI voices: alloy, echo, fable, onyx, nova, shimmer TODO: there are more voices add later
        return switch (genericVoice.toLowerCase()) {
            case "male1" -> "onyx";
            case "male2" -> "echo";
            case "female1" -> "nova";
            case "female2" -> "shimmer";
            case "neutral" -> "fable";
            default -> genericVoice.toLowerCase(); // Pass through if it's already an OpenAI voice name
        };
    }
}