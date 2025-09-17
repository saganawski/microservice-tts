package com.myorg.tts;

/**
 * Configuration for TTS conversion
 */
public class TtsConfig {
    private String voice;
    private String model;
    private double speed;
    private String language;
    private String instructions;
    private String responseFormat;

    // Default constructor with sensible defaults
    public TtsConfig() {
        this.voice = "alloy";
        this.model = "default";
        this.speed = 1.0;
        this.language = "en";
        this.instructions = null;
        this.responseFormat = "wav";
    }

    // Builder pattern for flexible configuration
    public static class Builder {
        private String voice = "alloy";
        private String model = "default";
        private double speed = 1.0;
        private String language = "en";
        private String instructions = null;
        private String responseFormat = "wav";

        public Builder withVoice(String voice) {
            this.voice = voice;
            return this;
        }

        public Builder withModel(String model) {
            this.model = model;
            return this;
        }

        public Builder withSpeed(double speed) {
            this.speed = speed;
            return this;
        }

        public Builder withLanguage(String language) {
            this.language = language;
            return this;
        }

        public Builder withInstructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder withResponseFormat(String responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public TtsConfig build() {
            TtsConfig config = new TtsConfig();
            config.voice = this.voice;
            config.model = this.model;
            config.speed = this.speed;
            config.language = this.language;
            config.instructions = this.instructions;
            config.responseFormat = this.responseFormat;
            return config;
        }
    }

    // Getters
    public String getVoice() {
        return voice;
    }

    public String getModel() {
        return model;
    }

    public double getSpeed() {
        return speed;
    }

    public String getLanguage() {
        return language;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    // Setters
    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }
}