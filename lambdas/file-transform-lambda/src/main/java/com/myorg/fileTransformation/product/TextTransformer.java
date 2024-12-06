package com.myorg.fileTransformation.product;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TextTransformer extends TransformFile {
    @Override
    public String transformFileContent(byte[] fileContent) {
        return new String(fileContent, StandardCharsets.UTF_8);
    }

    @Override
    public List<Map<String, byte[]>> transformFileContentToTokenSize(String fileContent, int tokenSize, String fileName) {
        final List<Map<String,byte[]>> splitContent = new java.util.ArrayList<>(List.of());

        final int fileSize = fileContent.length();
        int start = 0;
        int partNumber = 1;

        while(start < fileSize) {
            final int end = Math.min(start + tokenSize, fileSize);
            final byte[] part = fileContent.substring(start, end).getBytes(StandardCharsets.UTF_8);

            splitContent.add(Map.of(fileName + "-part-" + partNumber, part));
            start = end;
        }

        return splitContent;
    }
}
