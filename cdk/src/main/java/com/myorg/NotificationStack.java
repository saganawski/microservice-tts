package com.myorg;


import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.ses.EmailIdentity;
import software.amazon.awscdk.services.ses.Identity;
import software.constructs.Construct;

public class NotificationStack extends Stack {
    public NotificationStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final Function notificationLambda = Function.Builder.create(this, "NotificationLambda")
                .runtime(Runtime.JAVA_21)
                .code(Code.fromAsset("lambdas/notification-lambda/target/notification-lambda.jar"))
                .handler("com.myorg.NotificationLambda::handleRequest")
                .build();

        // SES setup for email notifications

        EmailIdentity.Builder.create(this, "NotificationEmailIdentity")
                .identity(Identity.email("noreply@example.com"))
                .build();

    }
}
