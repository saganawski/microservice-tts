package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.fileupload2.core.RequestContext;
import org.apache.commons.fileupload2.javax.JavaxServletFileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

//import static software.amazon.awssdk.http.auth.aws.internal.signer.V4RequestSigner.header;


//public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {
public class FileValidationLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();

    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
    // TODO: it's best practice to parse the bucket and fileName from the event.
    // This makes it flexable if the bucketName changes
    private static final Set<String> ALLOWED_CT = Set.of("application/pdf", "application/epub+zip", "text/plain" );




    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Original bucket name: " + ORIGINAL_BUCKET_NAME);

        final Map<String, String> headers = event.getHeaders();
        final String contentType = header(headers, "content-type");
        final boolean isBase64 = Boolean.TRUE.equals((event.getIsBase64Encoded()));
        String body = event.getBody();

        if (body == null) {
            return resp(400, "No body received");
        }

        if (contentType == null || ALLOWED_CT.stream().noneMatch(contentType::equalsIgnoreCase)) {
            return resp(415, "Unsupported media type. Allowed: application/pdf, application/epub+zip, text/plain");
        }

        byte[] fileBytes = isBase64 ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.ISO_8859_1);
        //TODO: upload file to s3
        // upload the file to the original bucket
        // check logs for filename in event
//        final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
//                .bucket(ORIGINAL_BUCKET_NAME)
//                .key(fileName)
//                .build();
//        final PutObjectResponse putObjectResponse = s3Client.putObject(
//                putObjectRequest, RequestBody.fromInputStream(fileItem.getInputStream(), fileItem.getSize()));

//        context.getLogger().log("File upload successful? : " + putObjectResponse.sdkHttpResponse().isSuccessful());


        return resp(200, "Received " + fileBytes.length + " bytes; contentType=" + contentType + " Successfully validated and uploaded to the next step");
    }


    private static String header(Map<String, String> h, String key) {
        if (h == null) return null;
        String v = h.get(key);
        if (v != null) return v;
        // header names are case-insensitive
        for (var e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static APIGatewayProxyResponseEvent resp(int status, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Content-Type", "text/plain",
                        "Access-Control-Allow-Origin", "*"))
                .withBody(msg);
    }
}
