package com.myorg;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.cognito.UserPool;
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
                    final Function validationLambda, final Function presignedUrlLambda,
                    final Function jobStatusLambda, final UserPool userPool) {
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

        // ============================================================
        // COGNITO AUTHORIZER (TICKET-016)
        // JWT-based auth — frontend sends Authorization: Bearer <idToken>
        // API key is kept as a secondary gate for rate limiting
        // ============================================================

        final CognitoUserPoolsAuthorizer cognitoAuthorizer = CognitoUserPoolsAuthorizer.Builder
                .create(this, "TtsCognitoAuthorizer")
                .authorizerName("tts-cognito-authorizer")
                .cognitoUserPools(List.of(userPool))
                .build();

        // ============================================================
        // API KEY + USAGE PLAN
        // ============================================================

        final ApiKey apiKey = ApiKey.Builder.create(this, "TtsApiKey")
                .apiKeyName("tts-frontend-key")
                .description("API key for TTS frontend application")
                .enabled(true)
                .build();

        final UsagePlan usagePlan = UsagePlan.Builder.create(this, "TtsUsagePlan")
                .name("TtsFrontendPlan")
                .description("Usage plan for TTS frontend")
                .throttle(ThrottleSettings.builder()
                        .rateLimit(10)    // 10 requests per second
                        .burstLimit(20)   // burst up to 20
                        .build())
                .quota(QuotaSettings.builder()
                        .limit(1000)                          // 1000 requests per day
                        .period(Period.DAY)
                        .build())
                .apiStages(List.of(UsagePlanPerApiStage.builder()
                        .api(api)
                        .stage(api.getDeploymentStage())
                        .build()))
                .build();

        usagePlan.addApiKey(apiKey);

        // Output the API Key ID so we can retrieve the value after deploy
        CfnOutput.Builder.create(this, "ApiKeyId")
                .value(apiKey.getKeyId())
                .description("API Key ID — retrieve value with: aws apigateway get-api-key --api-key <id> --include-value")
                .build();

        // ============================================================
        // ENDPOINTS (all require API key)
        // ============================================================

        final Resource fileUpload = api.getRoot().addResource("file-upload");

        // Create Lambda integration with proper permissions
        final LambdaIntegration lambdaIntegration = LambdaIntegration.Builder.create(validationLambda)
                .allowTestInvoke(true)
                .build();

        fileUpload.addMethod(
                "POST",
                lambdaIntegration,
                MethodOptions.builder()
                        .apiKeyRequired(true)
                        .authorizer(cognitoAuthorizer)
                        .authorizationType(AuthorizationType.COGNITO)
                        .requestParameters(Map.of(
                                "method.request.header.Content-Type", true
                        ))
                        .build()
        );

        // Explicitly grant API Gateway permission to invoke the Lambda
        validationLambda.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // CORS for file-upload endpoint
        addCorsOptions(fileUpload, "OPTIONS,POST");

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
                        .apiKeyRequired(true)
                        .authorizer(cognitoAuthorizer)
                        .authorizationType(AuthorizationType.COGNITO)
                        .requestParameters(Map.of(
                                "method.request.header.Content-Type", true
                        ))
                        .build()
        );

        // Grant permission to API Gateway to invoke the presigned URL Lambda
        presignedUrlLambda.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // CORS for presigned URL endpoint
        addCorsOptions(presignedUrl, "OPTIONS,POST");

        // Add job status endpoint: GET /status/{jobId}
        final Resource statusResource = api.getRoot().addResource("status");
        final Resource statusJobIdResource = statusResource.addResource("{jobId}");

        final LambdaIntegration statusIntegration = LambdaIntegration.Builder.create(jobStatusLambda)
                .allowTestInvoke(true)
                .build();

        statusJobIdResource.addMethod("GET", statusIntegration,
                MethodOptions.builder()
                        .apiKeyRequired(true)
                        .authorizer(cognitoAuthorizer)
                        .authorizationType(AuthorizationType.COGNITO)
                        .build());

        // Grant permission to invoke job status lambda
        jobStatusLambda.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // CORS for status endpoint
        addCorsOptions(statusJobIdResource, "OPTIONS,GET");
    }

    /**
     * Adds a CORS OPTIONS method to a resource. OPTIONS must NOT require an API key
     * (browsers send preflight requests without custom headers).
     */
    private void addCorsOptions(Resource resource, String allowMethods) {
        resource.addMethod("OPTIONS",
                MockIntegration.Builder.create()
                        .integrationResponses(List.of(
                                IntegrationResponse.builder()
                                        .statusCode("200")
                                        .responseParameters(Map.of(
                                                "method.response.header.Access-Control-Allow-Headers", "'Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent'",
                                                "method.response.header.Access-Control-Allow-Origin", "'*'",
                                                "method.response.header.Access-Control-Allow-Methods", "'" + allowMethods + "'"
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
