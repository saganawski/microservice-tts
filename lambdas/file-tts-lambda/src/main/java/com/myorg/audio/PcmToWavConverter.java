package com.myorg.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility to convert raw PCM audio data to WAV format
 */
public class PcmToWavConverter {

    /**
     * Convert raw PCM data to WAV format
     *
     * @param pcmData Raw PCM audio data
     * @param sampleRate Sample rate (e.g., 24000)
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param bitsPerSample Bits per sample (typically 16)
     * @return WAV file as byte array
     */
    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, short channels, short bitsPerSample) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(44);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + pcmData.length); // File size - 8
        buffer.put("WAVE".getBytes());

        // fmt chunk
        buffer.put("fmt ".getBytes());
        buffer.putInt(16); // fmt chunk size
        buffer.putShort((short) 1); // PCM format
        buffer.putShort(channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bitsPerSample / 8); // Byte rate
        buffer.putShort((short) (channels * bitsPerSample / 8)); // Block align
        buffer.putShort(bitsPerSample);

        // data chunk
        buffer.put("data".getBytes());
        buffer.putInt(pcmData.length);

        baos.write(buffer.array());
        baos.write(pcmData);

        return baos.toByteArray();
    }

    /**
     * Check if data is likely PCM (not WAV)
     * WAV files start with "RIFF", PCM data does not
     */
    public static boolean isPcmData(byte[] audioData) {
        if (audioData == null || audioData.length < 4) {
            return false;
        }
        String header = new String(audioData, 0, 4);
        return !header.equals("RIFF");
    }

    /**
     * Convert PCM data to WAV with default settings
     * Assumes 24kHz, mono, 16-bit (common for TTS)
     */
    public static byte[] pcmToWavDefault(byte[] pcmData) throws IOException {
        return pcmToWav(pcmData, 24000, (short) 1, (short) 16);
    }
}