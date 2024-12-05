package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
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

public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();


    @Override
    public String handleRequest(Map<String, Object> event, Context context) {

        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Original bucket name: " + ORIGINAL_BUCKET_NAME);

        final String body = (String) event.get("body");
        if(body == null) {
            return "Invalid request: No body found.";
        }

        final boolean isBase64Encoded = (boolean) event.get("isBase64Encoded");
        final byte[] bodyBytes = isBase64Encoded ? Base64.getDecoder().decode(body) : body.getBytes(StandardCharsets.UTF_8);
        try {
            final DiskFileItemFactory factory = DiskFileItemFactory.builder().get();
            final JavaxServletFileUpload fileUpload = new JavaxServletFileUpload(factory);

            //parse the request
            final List<FileItem> fileItems = fileUpload.parseRequest(new RequestContext(){

                @Override
                public String getCharacterEncoding() {
                    return StandardCharsets.UTF_8.name();
                }

                @Override
                public long getContentLength() {
                    return bodyBytes.length;
                }

                @Override
                public String getContentType() {
                    final Map<String, String> headers = (Map<String, String>) event.get("headers");
                    return headers.get("Content-Type");
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return new ByteArrayInputStream(bodyBytes);
                }
            });

            for(FileItem fileItem : fileItems) {
                if(!fileItem.isFormField()) {
                    final String fileName = fileItem.getName();
                    final String contentType = fileItem.getContentType();
                    context.getLogger().log("File name: " + fileName);
                    context.getLogger().log("Content type: " + contentType);

                    if(!isSupportedFileType(fileName, contentType)) {
                        return "File type not supported";
                    }

                    //upload the file to the original bucket
                    final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                            .bucket(ORIGINAL_BUCKET_NAME)
                            .key(fileName)
                            .build();
                    final PutObjectResponse putObjectResponse = s3Client.putObject(
                            putObjectRequest, RequestBody.fromInputStream(fileItem.getInputStream(), fileItem.getSize()));

                    context.getLogger().log("File upload response: " + putObjectResponse);
                    return "file uploaded successfully to " + ORIGINAL_BUCKET_NAME;
                }
            }

            return "Invalid request: No file found.";
        } catch (Exception e) {
            context.getLogger().log("Error decoding body: " + e.getMessage());
            return "Error uploading file to bucket: " + e.getMessage();
        } catch (Throwable t){
            context.getLogger().log("Error parsing request: " + t.getMessage());
            return "Error uploading file to bucket: " + t.getMessage();
        }
    }

    private boolean isSupportedFileType(String filename, String contentType) {
        if (filename == null || contentType == null) {
            return false;
        }

        // Check file extensions and MIME types
        return (filename.endsWith(".pdf") && contentType.equals("application/pdf"))
                || (filename.endsWith(".txt") && contentType.equals("text/plain"));
    }
}