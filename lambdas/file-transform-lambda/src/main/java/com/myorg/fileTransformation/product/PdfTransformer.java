package com.myorg.fileTransformation.product;

import java.util.List;
import java.util.Map;

public class PdfTransformer extends TransformFile {
    @Override
    public String transformFileContent(byte[] fileContent) {
        return "PDF file content";
    }

    @Override
    public List<Map<String, byte[]>> transformFileContentToTokenSize(String fileContent, int tokenSize, String fileName) {
        return List.of();
    }
}
