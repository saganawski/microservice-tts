
public class NotificationLambda implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Received event: " + event);
        context.getLogger().log("Context: " + context);
        // Send notification
        return "Notification sent";
    }
}