package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class ApiStack extends Stack {
    public ApiStack(final Construct scope, final String id, final StackProps props,
                    final Function validationLambda, final Function presignedUrlLambda) {
        super(scope, id, props);

        // create IAM role for API Gateway to write logs to CloudWatch
        final Role apiGatewayLogRole = Role.Builder.create(this, "ApiGatewayLogRole")
                .assumedBy(new ServicePrincipal("apigateway.amazonaws.com"))
                .description("IAM role for API Gateway to write logs to CloudWatch")
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonAPIGatewayPushToCloudWatchLogs")
                ))
                .build();

        // associate the IAM role with the API Gateway
        final CfnAccount cfnAccount = CfnAccount.Builder.create(this, "ApiGatewayAccount")
                .cloudWatchRoleArn(apiGatewayLogRole.getRoleArn())
                .build();

        final LogGroup apiLogGroup = LogGroup.Builder.create(this, "ApiGatewayLogGroup")
                .retention(RetentionDays.ONE_WEEK) // Set retention policy
                .logGroupName("ApiGatewayAccessLogs") // Custom log group name
                .build();

        // API Gateway for web interface
        final RestApi api = RestApi.Builder.create(this, "WebApi")
                .restApiName("FileProcessingApi")
                .description("This service serves as and entry point for users to upload files.")
                .binaryMediaTypes(List.of(
                        "multipart/form-data", // For file uploads via forms
                        "application/pdf",
                        "application/epub+zip", // EPUB
                        "text/plain"
                ))
                .deployOptions(StageOptions.builder()
                        .accessLogDestination(new LogGroupLogDestination(apiLogGroup))
                        .accessLogFormat(AccessLogFormat.custom(
                                "{ \"requestId\":\"$context.requestId\", " +
                                        "\"ip\":\"$context.identity.sourceIp\", " +
                                        "\"requestTime\":\"$context.requestTime\", " +
                                        "\"httpMethod\":\"$context.httpMethod\", " +
                                        "\"resourcePath\":\"$context.resourcePath\", " +
                                        "\"status\":\"$context.status\", " +
                                        "\"responseLength\":\"$context.responseLength\" }"
                        ))
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .dataTraceEnabled(true)
                        .build())
                .build();

        // Ensure the Stage depends on the CfnAccount resource
        api.getDeploymentStage().getNode().addDependency(cfnAccount);

        final Resource fileUpload = api.getRoot().addResource("file-upload");
        
        // Create Lambda integration with proper permissions
        final LambdaIntegration lambdaIntegration = LambdaIntegration.Builder.create(validationLambda)
                .allowTestInvoke(true)
                .build();
        
        fileUpload.addMethod(
                "POST",
                lambdaIntegration,
                MethodOptions.builder()
                        .requestParameters(Map.of(
                                "method.request.header.Content-Type", true
                        ))
                        .build()
        );
        
        // Explicitly grant API Gateway permission to invoke the Lambda
        validationLambda.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // Add presigned URL endpoint for large file uploads
        final Resource presignedUrl = api.getRoot().addResource("generate-upload-url");

        // Create Lambda integration for presigned URL generation
        final LambdaIntegration presignedUrlIntegration = LambdaIntegration.Builder.create(presignedUrlLambda)
                .allowTestInvoke(true)
                .build();

        presignedUrl.addMethod(
                "POST",
                presignedUrlIntegration,
                MethodOptions.builder()
                        .requestParameters(Map.of(
                                "method.request.header.Content-Type", true
                        ))
                        .build()
        );

        // Grant permission to API Gateway to invoke the presigned URL Lambda
        presignedUrlLambda.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // Add CORS support
        presignedUrl.addMethod("OPTIONS",
                MockIntegration.Builder.create()
                        .integrationResponses(List.of(
                                IntegrationResponse.builder()
                                        .statusCode("200")
                                        .responseParameters(Map.of(
                                                "method.response.header.Access-Control-Allow-Headers", "'Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token'",
                                                "method.response.header.Access-Control-Allow-Origin", "'*'",
                                                "method.response.header.Access-Control-Allow-Methods", "'OPTIONS,POST'"
                                        ))
                                        .build()
                        ))
                        .passthroughBehavior(PassthroughBehavior.WHEN_NO_MATCH)
                        .requestTemplates(Map.of(
                                "application/json", "{\"statusCode\": 200}"
                        ))
                        .build(),
                MethodOptions.builder()
                        .methodResponses(List.of(
                                MethodResponse.builder()
                                        .statusCode("200")
                                        .responseParameters(Map.of(
                                                "method.response.header.Access-Control-Allow-Headers", true,
                                                "method.response.header.Access-Control-Allow-Origin", true,
                                                "method.response.header.Access-Control-Allow-Methods", true
                                        ))
                                        .build()
                        ))
                        .build()
        );

    }

}
