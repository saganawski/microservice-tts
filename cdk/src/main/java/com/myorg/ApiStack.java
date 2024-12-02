package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.lambda.Function;
import software.constructs.Construct;

public class ApiStack extends Stack {
    public ApiStack(final Construct scope, final String id, final StackProps props, final Function validationLambda) {
        super(scope, id, props);

        // API Gateway for web interface
        final RestApi api = RestApi.Builder.create(this, "WebApi")
                .restApiName("FileProcessingApi")
                .description("This service serves as and entry point for users to upload files.")
                .build();
        final LambdaIntegration fileUploadIntegration = new LambdaIntegration(validationLambda);

        api.getRoot().addResource("file-upload").addMethod("POST", fileUploadIntegration);
    }
}
