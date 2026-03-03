package us.poliscore.polibench.providers;

import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockProvider implements AiProvider {
    private final List<String> lastRequestIds = new ArrayList<>();
    private final Map<String, Boolean> requestPassExpectations = new HashMap<>();

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

                // Construct the request row using the same JSONL shape as the live provider
                java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
                batchRequest.put("custom_id", req.getRequestId());
                batchRequest.put("method", "POST");
                batchRequest.put("url", "/v1/chat/completions");
                batchRequest.put("body", body);

                out.println(mapper.writeValueAsString(batchRequest));

                requestPassExpectations.put(req.getRequestId(),
                        "PASS".equalsIgnoreCase(req.getTask().getExpected()));
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
        requestPassExpectations.clear();

        try (BufferedReader reader = new BufferedReader(new FileReader(batchFileOutputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node = mapper.readTree(line);
                JsonNode idNode = node.get("custom_id");
                if (idNode != null && !idNode.asText().isBlank()) {
                    String requestId = idNode.asText();
                    lastRequestIds.add(requestId);
                }
            }
        }
    }

    private String buildMockResponseContent(String requestId) {
        if (requestPassExpectations.getOrDefault(requestId, false)) {
            return "Mock analysis found the policy structurally sound under the evaluated pillar. <PASS>";
        }

        return "Mock analysis detected a structural flaw in the policy design. <FAIL>";
    }
}
