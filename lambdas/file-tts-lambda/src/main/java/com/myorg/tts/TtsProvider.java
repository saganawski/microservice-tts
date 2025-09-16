package com.myorg.tts;

import java.io.IOException;

/**
 * Interface for Text-to-Speech providers
 */
public interface TtsProvider {
    /**
     * Convert text to speech audio
     *
     * @param text The text content to convert to speech
     * @param config Configuration for the TTS conversion
     * @return Audio data as byte array (typically MP3 format)
     * @throws IOException If the conversion fails
     * @throws InterruptedException If the conversion is interrupted
     */
    byte[] convertTextToSpeech(String text, TtsConfig config) throws IOException, InterruptedException;

    /**
     * Get the name of this TTS provider
     *
     * @return Provider name
     */
    String getProviderName();

    /**
     * Validate that the provider is properly configured
     *
     * @throws IllegalStateException If the provider is not configured correctly
     */
    void validateConfiguration() throws IllegalStateException;
}