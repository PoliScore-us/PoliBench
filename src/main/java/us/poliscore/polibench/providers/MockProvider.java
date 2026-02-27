package us.poliscore.polibench.providers;

import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import java.util.ArrayList;
import java.util.List;

public class MockProvider implements AiProvider {
    @Override
    public String getModelId() {
        return "mock";
    }

    @Override
    public void generateBatchFile(List<ModelRequest> requests, String batchFileOutputPath) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(batchFileOutputPath))) {
            for (ModelRequest req : requests) {
                // Construct the messages array
                java.util.List<java.util.Map<String, String>> messages = new java.util.ArrayList<>();
                messages.add(java.util.Map.of("role", "system", "content", req.getSystemPrompt()));
                messages.add(java.util.Map.of("role", "user", "content", req.getUserPrompt()));

                // Construct the body of the API request
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", "mock-model");
                body.put("messages", messages);
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
    public String submitBatch(String batchFileOutputPath) throws Exception {
        System.out.println("[MockProvider] Simulating batch submission for " + batchFileOutputPath);
        return "mock_batch_" + System.currentTimeMillis();
    }

    @Override
    public boolean isBatchComplete(String batchId) throws Exception {
        System.out.println("[MockProvider] Simulating batch completion check for " + batchId);
        // Instant completion for the mock driver
        return true;
    }

    @Override
    public List<ModelResponse> fetchBatchResults(String batchId) throws Exception {
        System.out.println("[MockProvider] Simulating result fetching for " + batchId);
        List<ModelResponse> responses = new ArrayList<>();
        // In a real scenario we'd parse the ids, but here we just return a few fake
        // ones
        // derived from a static known set or just relying on App generating random ones
        // that will fail evaluation.
        responses.add(new ModelResponse("5990a88d-dbc7-4c9d-a20b-d3e01ad6162a",
                "This fails to identify cause because color does not affect congestion. <FAIL>", 10, 10));
        responses.add(new ModelResponse("9e42b08d-104d-486d-beb1-ea7a34d09c03",
                "This lacks empirical support and is unscientific due to insufficient sample size. <FAIL>", 10, 10));
        responses.add(new ModelResponse("ce675c4e-db7d-4c7d-bd5c-7eb2608a4af9",
                "This relates to workforce shortage and infrastructure constraints. <FAIL>", 10, 10));
        return responses;
    }

    @Override
    public List<ModelResponse> parseBatchResults(String batchResultInputPath) throws Exception {
        System.out.println("[MockProvider] Simulating offline file parse for " + batchResultInputPath);
        return new ArrayList<>();
    }

    @Override
    public double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens) {
        return 0.0;
    }
}
