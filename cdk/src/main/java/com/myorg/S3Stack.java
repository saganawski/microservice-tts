package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.UUID;

public class S3Stack extends Stack {
    private final Bucket originalFileBucket;
    private final Bucket chunkFileBucket;
    private final Bucket processedFileBucket;

    //generate UUID to string
    private static final String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public S3Stack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final String uuid = generateUUID();

        originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket" + uuid)
                .versioned(false)
                .build();

        chunkFileBucket = Bucket.Builder.create(this, "ChunkFileBucket")
                .bucketName("chunk-file-bucket" + uuid)
                .versioned(false)
                .build();

        processedFileBucket = Bucket.Builder.create(this, "ProcessedFileBucket")
                .bucketName("processed-file-bucket" + uuid)
                .versioned(false)
                .build();
    }

    public Bucket getOriginalFileBucket() {
        return originalFileBucket;
    }

    public Bucket getChunkFileBucket() {
        return chunkFileBucket;
    }

    public Bucket getProcessedFileBucket() {
        return processedFileBucket;
    }
}
