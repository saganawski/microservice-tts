package com.myorg.mistral;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MistralApiClientTest {

    @Test
    void loadsApiKeyFromConstructor() {
        MistralApiClient client = new MistralApiClient("test-key");
        List<String> chunks = client.parseToChunks("hello world");
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    void missingApiKeyThrows() {
        MistralApiClient client = new MistralApiClient(null);
        assertThrows(IllegalStateException.class, () -> client.parseToChunks("data"));
    }

    @Test
    void splitsInto900CharChunks() {
        String content = "a".repeat(901);
        MistralApiClient client = new MistralApiClient("key");
        List<String> chunks = client.parseToChunks(content);
        assertEquals(2, chunks.size());
        assertEquals(900, chunks.get(0).length());
        assertEquals(1, chunks.get(1).length());
    }
}

