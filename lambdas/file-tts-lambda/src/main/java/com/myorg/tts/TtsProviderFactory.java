package com.myorg.tts;

import com.myorg.tts.providers.OpenAiTtsProvider;
import com.myorg.tts.providers.SelfHostedTtsProvider;

/**
 * Factory for creating TTS provider instances based on configuration
 */
public class TtsProviderFactory {

    public enum ProviderType {
        OPENAI,
        SELF_HOSTED
    }

    /**
     * Create a TTS provider based on environment configuration
     *
     * @return Configured TTS provider instance
     */
    public static TtsProvider createProvider() {
        String providerTypeStr = System.getenv("TTS_PROVIDER");
        if (providerTypeStr == null || providerTypeStr.isEmpty()) {
            providerTypeStr = "OPENAI"; // Default to OpenAI for backward compatibility
        }

        ProviderType providerType;
        try {
            providerType = ProviderType.valueOf(providerTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid TTS_PROVIDER: " + providerTypeStr +
                    ". Valid values are: OPENAI, SELF_HOSTED");
        }

        return createProvider(providerType);
    }

    /**
     * Create a TTS provider of the specified type
     *
     * @param type The provider type to create
     * @return Configured TTS provider instance
     */
    public static TtsProvider createProvider(ProviderType type) {
        switch (type) {
            case OPENAI:
                return createOpenAiProvider();
            case SELF_HOSTED:
                return createSelfHostedProvider();
            default:
                throw new IllegalArgumentException("Unsupported provider type: " + type);
        }
    }

    private static TtsProvider createOpenAiProvider() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is required for OpenAI provider");
        }
        return new OpenAiTtsProvider(apiKey);
    }

    private static TtsProvider createSelfHostedProvider() {
        String endpointUrl = System.getenv("TTS_ENDPOINT_URL");
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IllegalStateException("TTS_ENDPOINT_URL environment variable is required for self-hosted provider");
        }

        String apiKey = System.getenv("TTS_API_KEY"); // Optional
        String authHeader = System.getenv("TTS_AUTH_HEADER"); // Optional, defaults to "Authorization"

        if (apiKey != null && !apiKey.isEmpty()) {
            return new SelfHostedTtsProvider(endpointUrl, apiKey, authHeader);
        } else {
            return new SelfHostedTtsProvider(endpointUrl);
        }
    }

    /**
     * Create default TTS configuration based on environment variables
     *
     * @return TTS configuration
     */
    public static TtsConfig createDefaultConfig() {
        TtsConfig.Builder builder = new TtsConfig.Builder();

        String voice = System.getenv("TTS_VOICE");
        if (voice != null && !voice.isEmpty()) {
            builder.withVoice(voice);
        }

        String model = System.getenv("TTS_MODEL");
        if (model != null && !model.isEmpty()) {
            builder.withModel(model);
        }

        String speedStr = System.getenv("TTS_SPEED");
        if (speedStr != null && !speedStr.isEmpty()) {
            try {
                double speed = Double.parseDouble(speedStr);
                builder.withSpeed(speed);
            } catch (NumberFormatException e) {
                // Ignore invalid speed value, use default
            }
        }

        String language = System.getenv("TTS_LANGUAGE");
        if (language != null && !language.isEmpty()) {
            builder.withLanguage(language);
        }

        String instructions = System.getenv("TTS_INSTRUCTIONS");
        if (instructions != null && !instructions.isEmpty()) {
            builder.withInstructions(instructions);
        }

        String responseFormat = System.getenv("TTS_RESPONSE_FORMAT");
        if (responseFormat != null && !responseFormat.isEmpty()) {
            builder.withResponseFormat(responseFormat);
        }

        return builder.build();
    }
}