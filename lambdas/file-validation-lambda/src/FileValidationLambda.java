import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;

public class FileValidationLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String ORIGINAL_BUCKET_NAME = System.getenv("ORIGINAL_BUCKET_NAME");

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Original bucket name: " + ORIGINAL_BUCKET_NAME);
        context.getLogger().log("Context: " + context);
        // Validate the file
        //TODO: this should be a boolean and some actual validation on file type. I.E. PDF or Txt
        return "File is valid";
    }
}