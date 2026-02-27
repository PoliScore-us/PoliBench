package us.poliscore.polibench.providers;

public class OpenAIProvider implements AiProvider {

    private final String modelId;
    private final double inputCostPerMillion;
    private final double outputCostPerMillion;

    public OpenAIProvider(String modelId) {
        this.modelId = modelId;

        switch (modelId) {
            case "gpt-4o":
                this.inputCostPerMillion = 5.00;
                this.outputCostPerMillion = 15.00;
                break;
            case "gpt-4o-mini":
                this.inputCostPerMillion = 0.15;
                this.outputCostPerMillion = 0.60;
                break;
            default:
                throw new IllegalArgumentException("Unknown OpenAI model: " + modelId);
        }
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public void generateBatchFile(java.util.List<us.poliscore.polibench.models.ModelRequest> requests,
            String batchFileOutputPath) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(batchFileOutputPath))) {
            for (us.poliscore.polibench.models.ModelRequest req : requests) {

                // Construct the messages array
                java.util.List<java.util.Map<String, String>> messages = new java.util.ArrayList<>();
                messages.add(java.util.Map.of("role", "system", "content", req.getSystemPrompt()));
                messages.add(java.util.Map.of("role", "user", "content", req.getUserPrompt()));

                // Construct the body of the API request
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", this.modelId);
                body.put("messages", messages);
                // Optional constraints we might want to enforce
                body.put("temperature", 0.0);

                // Construct the required JSONL line layout for OpenAI Batch endpoint
                java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
                batchRequest.put("custom_id", req.getRequestId());
                batchRequest.put("method", "POST");
                batchRequest.put("url", "/v1/chat/completions");
                batchRequest.put("body", body);

                out.println(mapper.writeValueAsString(batchRequest));
            }
        }
    }

    @Override
    public java.util.List<us.poliscore.polibench.models.ModelResponse> parseBatchResults(String batchResultInputPath)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<us.poliscore.polibench.models.ModelResponse> responses = new java.util.ArrayList<>();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(batchResultInputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(line);

                String customId = root.path("custom_id").asText();
                com.fasterxml.jackson.databind.JsonNode responseBody = root.path("response").path("body");

                String content = "";
                int promptTokens = 0;
                int completionTokens = 0;

                if (!responseBody.isMissingNode() && responseBody.has("choices")) {
                    content = responseBody.path("choices").get(0).path("message").path("content").asText();
                    promptTokens = responseBody.path("usage").path("prompt_tokens").asInt();
                    completionTokens = responseBody.path("usage").path("completion_tokens").asInt();
                } else {
                    // Handle API errors for this specific row
                    com.fasterxml.jackson.databind.JsonNode errorNode = root.path("error");
                    if (!errorNode.isMissingNode()) {
                        content = "ERROR: " + errorNode.toString();
                    }
                }

                responses.add(new us.poliscore.polibench.models.ModelResponse(customId, content, promptTokens,
                        completionTokens));
            }
        }
        return responses;
    }

    @Override
    public double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens) {
        // OpenAI batch discount is exactly 50%
        double batchDiscountMultiplier = 0.5;

        double inputCost = (promptTokens / 1_000_000.0) * this.inputCostPerMillion;
        double outputCost = (expectedCompletionTokens / 1_000_000.0) * this.outputCostPerMillion;

        return (inputCost + outputCost) * batchDiscountMultiplier;
    }
}
