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

public class ApiStack extends Stack {
    public ApiStack(final Construct scope, final String id, final StackProps props, final Function validationLambda) {
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
                .deployOptions(StageOptions.builder()
                        .accessLogDestination(new LogGroupLogDestination(apiLogGroup))
                        .accessLogFormat(AccessLogFormat.custom(
                                        "{ \"requestId\":\"$context.requestId\", " +
                                                "\"ip\":\"$context.identity.sourceIp\", " +
                                                "\"caller\":\"$context.identity.caller\", " +
                                                "\"user\":\"$context.identity.user\", " +
                                                "\"requestTime\":\"$context.requestTime\", " +
                                                "\"httpMethod\":\"$context.httpMethod\", " +
                                                "\"resourcePath\":\"$context.resourcePath\", " +
                                                "\"status\":\"$context.status\", " +
                                                "\"responseLength\":\"$context.responseLength\" }"
                                ))
                        .loggingLevel(MethodLoggingLevel.INFO) // Log all request information
                        .dataTraceEnabled(true) // Enable full request/response logging
                        .build())
                .build();

        // Ensure the Stage depends on the CfnAccount resource
        api.getDeploymentStage().getNode().addDependency(cfnAccount);

        final LambdaIntegration fileUploadIntegration = new LambdaIntegration(validationLambda);

        api.getRoot().addResource("file-upload").addMethod("POST", fileUploadIntegration);
    }

}
