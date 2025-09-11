package com.myorg.fileTransformation.creator;

import com.myorg.fileTransformation.product.PdfTransformer;
import com.myorg.fileTransformation.product.TextTransformer;
import com.myorg.fileTransformation.product.TransformFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleFileTransformFactory {
    //add logger
    private final Logger logger = LoggerFactory.getLogger(SimpleFileTransformFactory.class);

    public TransformFile createTransformFile(String fileName) {
        if (fileName == null) {
            logger.error("File name is null");
            return null;
        }

        final String fileType = fileName.substring(fileName.lastIndexOf(".") + 1);
        logger.info("Creating transformer for file type: {}", fileType);

        return switch (fileType) {
            case "txt" -> new TextTransformer();
            case "pdf" -> new PdfTransformer();
            default -> {
                logger.error("Unsupported file type: {}", fileType);
                yield null;
            }
        };
    }
}
