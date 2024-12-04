package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");
    private static final Pattern FILE_DETAILS_PATTERN = Pattern.compile(
            "Content-Disposition: form-data; name=\"[^\"]*\"; filename=\"([^\"]*)\".*Content-Type: ([^\\s]*)",
            Pattern.DOTALL
    );

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {

        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Original bucket name: " + ORIGINAL_BUCKET_NAME);

        final String body = (String) event.get("body");
        if(body == null) {
            return "Invalid request: No body found.";
        }

        //parse the file details from the body
        final Matcher matcher = FILE_DETAILS_PATTERN.matcher(body);
        if(!matcher.find()) {
            return "Invalid request: No file details found.";
        }

        final String fileName = matcher.group(1);
        final String fileType = matcher.group(2);

        context.getLogger().log("File name: " + fileName);
        context.getLogger().log("File type: " + fileType);

        if(!isSupportedFileType(fileName, fileType)) {
            return "File type not supported";
        }

        context.getLogger().log("File is valid");
        //upload the file to the original bucket


        return "File is valid";
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