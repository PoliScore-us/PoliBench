package us.poliscore.polibench.providers;

import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MockProvider implements AiProvider {
    private static final Set<String> PASSING_REQUEST_IDS = new HashSet<>(Set.of(
            "dbfb2f57-c0db-41f5-9f46-cead4bc6346f",
            "a03f8f5d-af18-4a63-8b6a-7ee85e4f8988",
            "b130ff42-c9da-443a-bba2-1f33254d51a0",
            "938155e0-9f5f-4f60-b8e9-537cb77755f2",
            "85e4920c-3c63-4b8c-b1c8-f74b787b42d3",
            "31531f67-0489-413a-8858-74d85717fa8e",
            "d9c1ce4b-a88e-4e3b-a817-fa038f3c255b"));

    private final List<String> lastRequestIds = new ArrayList<>();

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
        cacheRequestIds(batchFileOutputPath);
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
        for (String requestId : lastRequestIds) {
            responses.add(new ModelResponse(requestId,
                    buildMockResponseContent(requestId),
                    10,
                    10));
        }
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

    private void cacheRequestIds(String batchFileOutputPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        lastRequestIds.clear();

        try (BufferedReader reader = new BufferedReader(new FileReader(batchFileOutputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node = mapper.readTree(line);
                JsonNode idNode = node.get("custom_id");
                if (idNode != null && !idNode.asText().isBlank()) {
                    lastRequestIds.add(idNode.asText());
                }
            }
        }
    }

    private String buildMockResponseContent(String requestId) {
        if (PASSING_REQUEST_IDS.contains(requestId)) {
            return "Mock analysis found the policy structurally sound under the evaluated pillar. <PASS>";
        }

        return "Mock analysis detected a structural flaw in the policy design. <FAIL>";
    }
}
