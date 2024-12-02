package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

public class S3Stack extends Stack {
    public S3Stack(final Construct scope, final String id, final StackProps props) {

        final Bucket originalFileBucket = Bucket.Builder.create(this, "OriginalFileBucket")
                .bucketName("original-file-bucket")
                .versioned(false)
                .build();

        final Bucket chunkFileBucket = Bucket.Builder.create(this, "ChunkFileBucket")
                .bucketName("chunk-file-bucket")
                .versioned(false)
                .build();

        final Bucket processedFileBucket = Bucket.Builder.create(this, "ProcessedFileBucket")
                .bucketName("processed-file-bucket")
                .versioned(false)
                .build();
    }
}
