package com.myorg.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for concatenating WAV files with crossfading to prevent clicks/pops
 */
public class WavConcatenator {

    private static final String RIFF_HEADER = "RIFF";
    private static final String WAVE_HEADER = "WAVE";
    private static final String FMT_CHUNK = "fmt ";
    private static final String DATA_CHUNK = "data";

    // Crossfade duration in milliseconds
    private static final int CROSSFADE_MS = 10;

    /**
     * Represents parsed WAV file data
     */
    private static class WavData {
        int sampleRate;
        short channels;
        short bitsPerSample;
        byte[] audioData;

        WavData(int sampleRate, short channels, short bitsPerSample, byte[] audioData) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.audioData = audioData;
        }
    }

    /**
     * Concatenates multiple WAV files with crossfading between chunks
     *
     * @param wavFiles List of WAV file byte arrays
     * @return Concatenated WAV file as byte array
     * @throws IOException if WAV parsing or processing fails
     */
    public static byte[] concatenateWithCrossfade(List<byte[]> wavFiles) throws IOException {
        if (wavFiles == null || wavFiles.isEmpty()) {
            throw new IllegalArgumentException("No WAV files to concatenate");
        }

        if (wavFiles.size() == 1) {
            return wavFiles.get(0);
        }

        // Parse all WAV files
        List<WavData> wavDataList = new ArrayList<>();
        for (byte[] wavFile : wavFiles) {
            wavDataList.add(parseWav(wavFile));
        }

        // Validate that all WAV files have the same format
        WavData firstWav = wavDataList.get(0);
        for (WavData wav : wavDataList) {
            if (wav.sampleRate != firstWav.sampleRate ||
                wav.channels != firstWav.channels ||
                wav.bitsPerSample != firstWav.bitsPerSample) {
                throw new IllegalArgumentException("All WAV files must have the same format");
            }
        }

        // Calculate crossfade parameters
        int bytesPerSample = firstWav.bitsPerSample / 8;
        int samplesPerMs = firstWav.sampleRate / 1000;
        int crossfadeSamples = samplesPerMs * CROSSFADE_MS;
        int crossfadeBytes = crossfadeSamples * bytesPerSample * firstWav.channels;

        // Calculate total output size
        int totalSize = 0;
        for (WavData wav : wavDataList) {
            totalSize += wav.audioData.length;
        }
        // Subtract overlap regions
        totalSize -= crossfadeBytes * (wavDataList.size() - 1);

        // Concatenate with crossfading
        byte[] outputData = new byte[totalSize];
        int outputPos = 0;

        for (int i = 0; i < wavDataList.size(); i++) {
            WavData currentWav = wavDataList.get(i);
            byte[] currentData = currentWav.audioData;

            if (i == 0) {
                // First chunk - copy all except potentially the last crossfade region
                if (wavDataList.size() > 1) {
                    int copyLength = currentData.length - crossfadeBytes;
                    System.arraycopy(currentData, 0, outputData, outputPos, copyLength);
                    outputPos += copyLength;
                } else {
                    System.arraycopy(currentData, 0, outputData, outputPos, currentData.length);
                    outputPos += currentData.length;
                }
            } else if (i == wavDataList.size() - 1) {
                // Last chunk - apply crossfade at beginning, copy rest
                byte[] previousTail = new byte[crossfadeBytes];
                WavData previousWav = wavDataList.get(i - 1);
                System.arraycopy(previousWav.audioData,
                               previousWav.audioData.length - crossfadeBytes,
                               previousTail, 0, crossfadeBytes);

                byte[] currentHead = new byte[crossfadeBytes];
                System.arraycopy(currentData, 0, currentHead, 0, crossfadeBytes);

                byte[] crossfaded = applyCrossfade(previousTail, currentHead,
                                                  firstWav.bitsPerSample,
                                                  firstWav.channels);

                // Write crossfaded region
                System.arraycopy(crossfaded, 0, outputData, outputPos, crossfadeBytes);
                outputPos += crossfadeBytes;

                // Write rest of current chunk
                System.arraycopy(currentData, crossfadeBytes, outputData, outputPos,
                               currentData.length - crossfadeBytes);
                outputPos += currentData.length - crossfadeBytes;
            } else {
                // Middle chunk - apply crossfade at both ends
                byte[] previousTail = new byte[crossfadeBytes];
                WavData previousWav = wavDataList.get(i - 1);
                System.arraycopy(previousWav.audioData,
                               previousWav.audioData.length - crossfadeBytes,
                               previousTail, 0, crossfadeBytes);

                byte[] currentHead = new byte[crossfadeBytes];
                System.arraycopy(currentData, 0, currentHead, 0, crossfadeBytes);

                byte[] crossfaded = applyCrossfade(previousTail, currentHead,
                                                  firstWav.bitsPerSample,
                                                  firstWav.channels);

                // Write crossfaded region
                System.arraycopy(crossfaded, 0, outputData, outputPos, crossfadeBytes);
                outputPos += crossfadeBytes;

                // Write middle part (excluding both crossfade regions)
                int middleLength = currentData.length - 2 * crossfadeBytes;
                System.arraycopy(currentData, crossfadeBytes, outputData, outputPos, middleLength);
                outputPos += middleLength;
            }
        }

        // Create output WAV file
        return createWavFile(firstWav.sampleRate, firstWav.channels,
                            firstWav.bitsPerSample, outputData);
    }

    /**
     * Parse WAV file header and extract audio data
     */
    private static WavData parseWav(byte[] wavFile) throws IOException {
        if (wavFile == null || wavFile.length < 44) {
            throw new IOException("Invalid WAV file: too small (size: " +
                (wavFile != null ? wavFile.length : 0) + ")");
        }

        ByteBuffer buffer = ByteBuffer.wrap(wavFile);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Check RIFF header
        byte[] riffHeader = new byte[4];
        buffer.get(riffHeader);
        String riffStr = new String(riffHeader);
        if (!riffStr.equals(RIFF_HEADER)) {
            throw new IOException("Invalid WAV file: missing RIFF header, found: " + riffStr);
        }

        int fileSize = buffer.getInt();
        // Validate file size (should be total size - 8)
        if (fileSize + 8 != wavFile.length && fileSize + 8 != wavFile.length - 1) {
            System.err.println("Warning: WAV file size mismatch. Header: " + (fileSize + 8) +
                             ", Actual: " + wavFile.length);
        }

        // Check WAVE header
        byte[] waveHeader = new byte[4];
        buffer.get(waveHeader);
        String waveStr = new String(waveHeader);
        if (!waveStr.equals(WAVE_HEADER)) {
            throw new IOException("Invalid WAV file: missing WAVE header, found: " + waveStr);
        }

        // Find and parse fmt chunk
        int sampleRate = 0;
        short channels = 0;
        short bitsPerSample = 0;
        byte[] audioData = null;

        while (buffer.remaining() >= 8) { // Need at least 8 bytes for chunk header
            byte[] chunkId = new byte[4];
            buffer.get(chunkId);
            int chunkSize = buffer.getInt();

            String chunkIdStr = new String(chunkId).trim();

            // Validate chunk size
            if (chunkSize < 0 || chunkSize > buffer.remaining()) {
                // Handle corrupted or unexpected chunk size
                System.err.println("Invalid chunk size: " + chunkSize + " for chunk: " + chunkIdStr);
                break;
            }

            if (chunkIdStr.equals(FMT_CHUNK) || chunkIdStr.startsWith("fmt")) {
                int startPos = buffer.position();
                short audioFormat = buffer.getShort();
                if (audioFormat != 1) { // PCM
                    throw new IOException("Only PCM WAV files are supported");
                }
                channels = buffer.getShort();
                sampleRate = buffer.getInt();
                buffer.getInt(); // Byte rate
                buffer.getShort(); // Block align
                bitsPerSample = buffer.getShort();

                // Skip any remaining format bytes
                int bytesRead = buffer.position() - startPos;
                if (bytesRead < chunkSize) {
                    buffer.position(startPos + chunkSize);
                }
            } else if (chunkIdStr.equals(DATA_CHUNK) || chunkIdStr.startsWith("data")) {
                // Validate audio data size
                if (chunkSize > 0 && chunkSize <= buffer.remaining()) {
                    audioData = new byte[chunkSize];
                    buffer.get(audioData);
                } else {
                    // If size is invalid, try to read remaining data
                    audioData = new byte[buffer.remaining()];
                    buffer.get(audioData);
                    break;
                }
            } else {
                // Skip unknown chunk
                if (chunkSize > 0 && chunkSize <= buffer.remaining()) {
                    buffer.position(buffer.position() + chunkSize);
                } else {
                    break;
                }
            }

            // Handle padding byte for odd-sized chunks (WAV spec requirement)
            if (chunkSize % 2 == 1 && buffer.hasRemaining()) {
                buffer.get(); // Skip padding byte
            }
        }

        if (audioData == null) {
            throw new IOException("Invalid WAV file: no audio data found");
        }

        return new WavData(sampleRate, channels, bitsPerSample, audioData);
    }

    /**
     * Apply crossfade between two audio segments
     */
    private static byte[] applyCrossfade(byte[] fadeOut, byte[] fadeIn,
                                        short bitsPerSample, short channels) {
        if (fadeOut.length != fadeIn.length) {
            throw new IllegalArgumentException("Crossfade segments must be same length");
        }

        byte[] result = new byte[fadeOut.length];
        int bytesPerSample = bitsPerSample / 8;
        int numSamples = fadeOut.length / (bytesPerSample * channels);

        if (bitsPerSample == 16) {
            // Process 16-bit samples
            ByteBuffer outBuffer = ByteBuffer.wrap(fadeOut).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer inBuffer = ByteBuffer.wrap(fadeIn).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer resultBuffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < numSamples; i++) {
                double fadeOutFactor = 1.0 - ((double) i / numSamples);
                double fadeInFactor = (double) i / numSamples;

                for (int ch = 0; ch < channels; ch++) {
                    short outSample = outBuffer.getShort();
                    short inSample = inBuffer.getShort();

                    // Apply crossfade
                    int mixed = (int)(outSample * fadeOutFactor + inSample * fadeInFactor);

                    // Clamp to 16-bit range
                    mixed = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));

                    resultBuffer.putShort((short) mixed);
                }
            }
        } else {
            throw new UnsupportedOperationException("Only 16-bit audio is currently supported");
        }

        return result;
    }

    /**
     * Create a WAV file from raw audio data
     */
    private static byte[] createWavFile(int sampleRate, short channels,
                                       short bitsPerSample, byte[] audioData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(44); // WAV header size
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put(RIFF_HEADER.getBytes());
        buffer.putInt(36 + audioData.length); // File size - 8
        buffer.put(WAVE_HEADER.getBytes());

        // fmt chunk
        buffer.put(FMT_CHUNK.getBytes());
        buffer.putInt(16); // fmt chunk size
        buffer.putShort((short) 1); // PCM format
        buffer.putShort(channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bitsPerSample / 8); // Byte rate
        buffer.putShort((short) (channels * bitsPerSample / 8)); // Block align
        buffer.putShort(bitsPerSample);

        // data chunk
        buffer.put(DATA_CHUNK.getBytes());
        buffer.putInt(audioData.length);

        baos.write(buffer.array());
        baos.write(audioData);

        return baos.toByteArray();
    }
}