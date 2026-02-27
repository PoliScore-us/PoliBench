package us.poliscore.polibench.models;

public class ModelResponse {
    private String requestId;
    private String content;
    private int promptTokens;
    private int completionTokens;

    public ModelResponse(String requestId, String content, int promptTokens, int completionTokens) {
        this.requestId = requestId;
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }
}
