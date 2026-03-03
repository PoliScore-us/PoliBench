package us.poliscore.polibench.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OpenRouterProvider implements AiProvider {
    private static final String OPENROUTER_API_BASE = "https://openrouter.ai/api/v1";

    private final String modelId;
    private final String apiKey;
    private final String referer;
    private final String appTitle;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Map<String, Path> batchOutputs;

    private BigDecimal promptCostPerToken;
    private BigDecimal completionCostPerToken;
    private boolean pricingLookupAttempted;

    public OpenRouterProvider(String modelId) {
        this.modelId = modelId;
        this.apiKey = ConfigLoader.getProperty("openrouter.api.key");
        this.referer = ConfigLoader.getProperty("openrouter.http.referer",
                ConfigLoader.getProperty("openrouter.site.url"));
        this.appTitle = ConfigLoader.getProperty("openrouter.title",
                ConfigLoader.getProperty("openrouter.app.title", "PoliBench"));
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        this.mapper = new ObjectMapper();
        this.batchOutputs = new HashMap<>();
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public void generateBatchFile(List<ModelRequest> requests, String batchFileOutputPath) throws Exception {
        try (PrintWriter out = new PrintWriter(new FileWriter(batchFileOutputPath))) {
            for (ModelRequest req : requests) {
                List<Map<String, String>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", req.getSystemPrompt()));
                messages.add(Map.of("role", "user", "content", req.getUserPrompt()));

                Map<String, Object> body = new HashMap<>();
                body.put("model", modelId);
                body.put("messages", messages);
                body.put("temperature", 0.0);
                body.put("usage", Map.of("include", true));

                Map<String, Object> batchRequest = new HashMap<>();
                batchRequest.put("custom_id", req.getRequestId());
                batchRequest.put("body", body);

                out.println(mapper.writeValueAsString(batchRequest));
            }
        }
    }

    @Override
    public String submitBatch(String batchFileOutputPath) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing openrouter.api.key in polibench.properties");
        }

        String batchId = "openrouter_batch_" + UUID.randomUUID();
        Path outputPath = Path.of("results", "openrouter_batch_output_" + batchId + ".jsonl");
        Files.createDirectories(outputPath.getParent());

        System.out.println("Submitting requests to OpenRouter...");
        executePseudoBatch(batchFileOutputPath, outputPath);
        batchOutputs.put(batchId, outputPath);
        System.out.println("OpenRouter batch results saved to: " + outputPath.toAbsolutePath());
        return batchId;
    }

    @Override
    public boolean isBatchComplete(String batchId) {
        Path outputPath = batchOutputs.get(batchId);
        return outputPath != null && Files.exists(outputPath);
    }

    @Override
    public List<ModelResponse> fetchBatchResults(String batchId) throws Exception {
        Path outputPath = batchOutputs.get(batchId);
        if (outputPath == null || !Files.exists(outputPath)) {
            throw new IllegalStateException("No completed OpenRouter batch output found for batch ID: " + batchId);
        }

        return parseBatchResults(outputPath.toString());
    }

    @Override
    public List<ModelResponse> parseBatchResults(String batchResultInputPath) throws Exception {
        List<ModelResponse> responses = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(batchResultInputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode root = mapper.readTree(line);
                String customId = root.path("custom_id").asText();
                JsonNode responseBody = root.path("response").path("body");

                String content = "";
                int promptTokens = 0;
                int completionTokens = 0;

                if (!responseBody.isMissingNode() && responseBody.has("choices")) {
                    JsonNode contentNode = responseBody.path("choices").get(0).path("message").path("content");
                    if (contentNode.isTextual()) {
                        content = contentNode.asText();
                    } else if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                        content = contentNode.toString();
                    }

                    promptTokens = responseBody.path("usage").path("prompt_tokens").asInt();
                    completionTokens = responseBody.path("usage").path("completion_tokens").asInt();
                } else {
                    JsonNode errorNode = root.path("error");
                    if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                        content = "ERROR: " + errorNode.toString();
                    }
                }

                responses.add(new ModelResponse(customId, content, promptTokens, completionTokens));
            }
        }

        return responses;
    }

    @Override
    public double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens) {
        loadPricingIfNeeded();

        if (promptCostPerToken == null || completionCostPerToken == null) {
            return 0.0;
        }

        BigDecimal inputCost = promptCostPerToken.multiply(BigDecimal.valueOf(promptTokens));
        BigDecimal outputCost = completionCostPerToken.multiply(BigDecimal.valueOf(expectedCompletionTokens));
        return inputCost.add(outputCost).doubleValue();
    }

    private void executePseudoBatch(String batchFileOutputPath, Path outputPath) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(batchFileOutputPath));
                PrintWriter out = new PrintWriter(new FileWriter(outputPath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode requestNode = mapper.readTree(line);
                String customId = requestNode.path("custom_id").asText();
                JsonNode bodyNode = requestNode.path("body");

                Map<String, Object> resultRow = new HashMap<>();
                resultRow.put("custom_id", customId);

                try {
                    HttpResponse<String> response = httpClient.send(buildCompletionRequest(bodyNode),
                            HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        resultRow.put("response", Map.of(
                                "status_code", response.statusCode(),
                                "body", mapper.readTree(response.body())));
                    } else {
                        resultRow.put("error", buildErrorPayload(response.statusCode(), response.body()));
                    }
                } catch (Exception e) {
                    resultRow.put("error", Map.of(
                            "message", e.getMessage(),
                            "type", e.getClass().getSimpleName()));
                }

                out.println(mapper.writeValueAsString(resultRow));
            }
        }
    }

    private HttpRequest buildCompletionRequest(JsonNode bodyNode) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_API_BASE + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(bodyNode)));

        if (referer != null && !referer.isBlank()) {
            builder.header("HTTP-Referer", referer);
        }
        if (appTitle != null && !appTitle.isBlank()) {
            builder.header("X-Title", appTitle);
            builder.header("X-OpenRouter-Title", appTitle);
        }

        return builder.build();
    }

    private Map<String, Object> buildErrorPayload(int statusCode, String body) {
        Map<String, Object> error = new HashMap<>();
        error.put("status_code", statusCode);
        error.put("body", body);
        return error;
    }

    private void loadPricingIfNeeded() {
        if (pricingLookupAttempted) {
            return;
        }
        pricingLookupAttempted = true;

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(OPENROUTER_API_BASE + "/models"))
                    .header("Content-Type", "application/json")
                    .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("WARNING: Unable to retrieve OpenRouter pricing for model " + modelId
                        + ". Cost estimate will be reported as $0.00.");
                return;
            }

            JsonNode root = mapper.readTree(response.body());
            for (JsonNode modelNode : root.path("data")) {
                String id = modelNode.path("id").asText();
                String canonicalSlug = modelNode.path("canonical_slug").asText();

                if (modelId.equals(id) || modelId.equals(canonicalSlug)) {
                    JsonNode pricing = modelNode.path("pricing");
                    promptCostPerToken = parseDecimal(pricing.path("prompt").asText());
                    completionCostPerToken = parseDecimal(pricing.path("completion").asText());
                    return;
                }
            }

            System.err.println("WARNING: No OpenRouter pricing entry found for model " + modelId
                    + ". Cost estimate will be reported as $0.00.");
        } catch (Exception e) {
            System.err.println("WARNING: Failed to retrieve OpenRouter pricing for model " + modelId
                    + ". Cost estimate will be reported as $0.00. Reason: " + e.getMessage());
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}
