package com.myorg.fileTransformation.creator;

import com.myorg.fileTransformation.product.TextTransformer;
import com.myorg.fileTransformation.product.TransformFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleFileTransformFactoryTest {

    @Test
    public void testCreateTransformFile_PositiveExample() {
        SimpleFileTransformFactory factory = new SimpleFileTransformFactory();
        TransformFile transformer = factory.createTransformFile("proff-concept-output.txt");
        assertNotNull(transformer);
        assertInstanceOf(TextTransformer.class, transformer);
    }

    @Test
    public void testCreateTransformFile_NegativeExample() {
        SimpleFileTransformFactory factory = new SimpleFileTransformFactory();
        TransformFile transformer = factory.createTransformFile("sometFile.csv");
        assertNull(transformer);
    }
}