import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;

public class TransformLambda implements RequestHandler<Map<String, Object>, String> {

        @Override
        public String handleRequest(Map<String, Object> event, com.amazonaws.services.lambda.runtime.Context context) {
            context.getLogger().log("Transforming file");
            context.getLogger().log("Received event: " + event);
            context.getLogger().log("Context: " + context);
            // Transform the file
            return "File has been transformed";
        }
}
