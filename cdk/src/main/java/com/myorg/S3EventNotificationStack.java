package com.myorg;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;
import software.amazon.awscdk.Stack;

public class S3EventNotificationStack extends Stack {

    public S3EventNotificationStack(final Construct scope, final String id, final StackProps props,
                                    Bucket originalFileBucket, Bucket chunkFileBucket, Function transformLambda, Function ttsLambda) {
        super(scope, id, props);

        originalFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(transformLambda));
        chunkFileBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(ttsLambda));
    }
}
