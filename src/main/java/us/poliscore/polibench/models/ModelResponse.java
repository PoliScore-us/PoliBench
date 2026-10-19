package us.poliscore.polibench.models;

public class ModelResponse {
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Attempt {
        private String finishReason;
        private String nativeFinishReason;
        private String provider;
        private String responseId;
        private String resolvedModel;
        private int maxCompletionTokens;
        private int promptTokens;
        private int completionTokens;
        private Integer reasoningTokens;
        private Integer visibleOutputTokens;
        private java.math.BigDecimal costUsd;
    }

    @lombok.Getter @lombok.Setter
    private java.util.List<Attempt> attempts = new java.util.ArrayList<>();

    public boolean isIncomplete() {
        if (content == null || content.isBlank()) return true;
        if (attempts.isEmpty()) return false; // Legacy imports have no completion metadata.
        var last = attempts.get(attempts.size() - 1);
        return (last.getFinishReason() != null && !"stop".equalsIgnoreCase(last.getFinishReason()))
                || "MAX_TOKENS".equalsIgnoreCase(last.getNativeFinishReason());
    }

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
