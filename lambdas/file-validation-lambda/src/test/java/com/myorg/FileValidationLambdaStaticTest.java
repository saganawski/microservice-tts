package com.myorg;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileValidationLambdaStaticTest {

    @Test
    void testExtractFileName_WithContentDisposition_ReturnsOriginalName() {
        // Arrange
        String contentDisposition = "attachment; filename=document.pdf";
        String contentType = "application/pdf";

        // Act
        String result = FileValidationLambda.extractFileName(contentDisposition, contentType);

        // Assert
        assertEquals("document.pdf", result);
    }

    @Test
    void testExtractFileName_WithQuotedFilename_RemovesQuotes() {
        // Arrange
        String contentDisposition = "attachment; filename=\"my document.pdf\"";
        String contentType = "application/pdf";

        // Act
        String result = FileValidationLambda.extractFileName(contentDisposition, contentType);

        // Assert
        assertEquals("my document.pdf", result);
    }

    @Test
    void testExtractFileName_NoContentDisposition_GeneratesFallback() {
        // Arrange
        String contentType = "application/pdf";

        // Act
        String result = FileValidationLambda.extractFileName(null, contentType);

        // Assert
        assertTrue(result.startsWith("upload_"));
        assertTrue(result.endsWith(".pdf"));
    }

    @Test
    void testExtractFileName_PdfFallback_HasCorrectExtension() {
        // Act
        String result = FileValidationLambda.extractFileName(null, "application/pdf");

        // Assert
        assertTrue(result.endsWith(".pdf"));
    }

    @Test
    void testExtractFileName_TextFallback_HasCorrectExtension() {
        // Act
        String result = FileValidationLambda.extractFileName(null, "text/plain");

        // Assert
        assertTrue(result.endsWith(".txt"));
    }

    @Test
    void testExtractFileName_EpubFallback_HasCorrectExtension() {
        // Act
        String result = FileValidationLambda.extractFileName(null, "application/epub+zip");

        // Assert
        assertTrue(result.endsWith(".epub"));
    }

    @Test
    void testExtractFileName_UnknownType_NoExtension() {
        // Act
        String result = FileValidationLambda.extractFileName(null, "unknown/type");

        // Assert
        assertTrue(result.startsWith("upload_"));
        assertFalse(result.contains("."));
    }

    @Test
    void testExtractFileName_ComplexContentDisposition_ExtractsCorrectFilename() {
        // Arrange
        String contentDisposition = "form-data; name=\"file\"; filename=\"test-file-123.pdf\"";
        String contentType = "application/pdf";

        // Act
        String result = FileValidationLambda.extractFileName(contentDisposition, contentType);

        // Assert
        assertEquals("test-file-123.pdf", result);
    }

    @Test
    void testExtractFileName_MalformedContentDisposition_UsesFallback() {
        // Arrange
        String contentDisposition = "attachment; badformat";
        String contentType = "text/plain";

        // Act
        String result = FileValidationLambda.extractFileName(contentDisposition, contentType);

        // Assert
        assertTrue(result.startsWith("upload_"));
        assertTrue(result.endsWith(".txt"));
    }

    @Test
    void testHeader_CaseInsensitive_FindsContentType() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/pdf");

        // Act
        String result = FileValidationLambda.header(headers, "content-type");

        // Assert
        assertEquals("application/pdf", result);
    }

    @Test
    void testHeader_ExactMatch_ReturnsValue() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");

        // Act
        String result = FileValidationLambda.header(headers, "content-type");

        // Assert
        assertEquals("text/plain", result);
    }

    @Test
    void testHeader_NullHeaders_ReturnsNull() {
        // Act
        String result = FileValidationLambda.header(null, "content-type");

        // Assert
        assertNull(result);
    }

    @Test
    void testHeader_MissingKey_ReturnsNull() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("other-header", "value");

        // Act
        String result = FileValidationLambda.header(headers, "content-type");

        // Assert
        assertNull(result);
    }

    @Test
    void testHeader_MultipleCaseVariations_FindsMatch() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Length", "1024");
        headers.put("X-Custom-Header", "custom-value");

        // Act & Assert
        assertEquals("application/json", FileValidationLambda.header(headers, "accept"));
        assertEquals("application/json", FileValidationLambda.header(headers, "ACCEPT"));
        assertEquals("1024", FileValidationLambda.header(headers, "content-length"));
        assertEquals("custom-value", FileValidationLambda.header(headers, "x-custom-header"));
    }

    @Test
    void testHeader_EmptyValue_ReturnsEmptyString() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("empty-header", "");

        // Act
        String result = FileValidationLambda.header(headers, "empty-header");

        // Assert
        assertEquals("", result);
    }

    @Test
    void testHeader_NullValue_ReturnsNull() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("null-header", null);

        // Act
        String result = FileValidationLambda.header(headers, "null-header");

        // Assert
        assertNull(result);
    }
}