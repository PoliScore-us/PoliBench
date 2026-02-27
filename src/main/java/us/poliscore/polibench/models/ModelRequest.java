package us.poliscore.polibench.models;

public class ModelRequest {
    private String requestId;
    private String systemPrompt;
    private String userPrompt;

    public ModelRequest(String requestId, String systemPrompt, String userPrompt) {
        this.requestId = requestId;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }
}
