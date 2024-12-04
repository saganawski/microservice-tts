package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;

public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Original bucket name: " + ORIGINAL_BUCKET_NAME);
        // TODO: get the file type from event.filename or some property in the event
        //TODO: if file type is not PDF or Txt, return "File type not supported"
        //TODO: if file type is PDF or Txt, return file is valid and upload file to original bucket

        return "File is valid";
    }
}