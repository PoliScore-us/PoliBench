package us.poliscore.polibench.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public class OpenRouterProvider implements AiProvider {
    private static final String OPENROUTER_API_BASE = "https://openrouter.ai/api/v1";
    private static final int THREADS = Integer.getInteger("polibench.openrouter.threads", 8);
    private static final int REQUESTS_PER_MINUTE = Integer.getInteger("polibench.openrouter.rpm", 120);
    private static final boolean FAIL_FAST = Boolean
            .parseBoolean(System.getProperty("polibench.openrouter.failFast", "true"));

    private final String modelId;
    private final String apiKey;
    private final String referer;
    private final String appTitle;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

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
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public List<ModelResponse> executeRequests(List<ModelRequest> requests) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing openrouter.api.key in polibench.properties");
        }
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        int workerCount = Math.max(1, Math.min(THREADS, requests.size()));
        System.out.println("Submitting " + requests.size() + " OpenRouter requests with "
                + workerCount + " worker threads...");

        BlockingQueue<ModelRequest> work = new LinkedBlockingQueue<>(requests);
        BlockingQueue<ModelResponse> completed = new LinkedBlockingQueue<>();
        CountDownLatch workersDone = new CountDownLatch(workerCount);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        GlobalRateGate rateGate = new GlobalRateGate(REQUESTS_PER_MINUTE);
        Object writeLock = new Object();

        Path outputPath = Path.of("results", "openrouter_batch_output_" + UUID.randomUUID() + ".jsonl");
        Files.createDirectories(outputPath.getParent());
        System.out.println("Storing responses in: " + outputPath.toAbsolutePath());

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("polibench-openrouter-" + thread.getId());
                thread.setDaemon(false);
                return thread;
            });

            try {
                for (int i = 0; i < workerCount; i++) {
                    pool.submit(() -> {
                        try {
                            while (!stop.get()) {
                                ModelRequest request = work.poll(250, TimeUnit.MILLISECONDS);
                                if (request == null) {
                                    break;
                                }

                                if (FAIL_FAST && fatal.get() != null) {
                                    break;
                                }

                                rateGate.acquire();
                                try {
                                    RequestOutcome outcome = executeRequest(request);
                                    completed.put(outcome.response());
                                    synchronized (writeLock) {
                                        writer.write(outcome.resultLine());
                                        writer.newLine();
                                        writer.flush();
                                    }
                                } catch (Throwable t) {
                                    fatal.compareAndSet(null, t);
                                    if (FAIL_FAST) {
                                        stop.set(true);
                                        work.clear();
                                        break;
                                    }
                                }
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            workersDone.countDown();
                        }
                    });
                }

                workersDone.await();
            } finally {
                pool.shutdownNow();
            }
        }

        if (fatal.get() != null) {
            throw toException("OpenRouter execution failed for model " + modelId
                    + ". Partial results are in " + outputPath.toAbsolutePath(), fatal.get());
        }

        if (completed.size() != requests.size()) {
            throw new IllegalStateException("OpenRouter execution returned " + completed.size() + " responses for "
                    + requests.size() + " requests.");
        }

        Map<String, ModelResponse> responsesById = completed.stream()
                .collect(Collectors.toMap(ModelResponse::getRequestId, response -> response, (left, right) -> left));

        List<ModelResponse> orderedResponses = new ArrayList<>(requests.size());
        for (ModelRequest request : requests) {
            ModelResponse response = responsesById.get(request.getRequestId());
            if (response == null) {
                throw new IllegalStateException("Missing OpenRouter response for request: " + request.getRequestId());
            }
            orderedResponses.add(response);
        }

        return orderedResponses;
    }

    @Override
    public List<ModelResponse> parseBatchResults(String batchResultInputPath) throws Exception {
        List<ModelResponse> responses = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(batchResultInputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                JsonNode root = mapper.readTree(line);
                responses.add(parseResultRow(root));
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

    private RequestOutcome executeRequest(ModelRequest modelRequest) throws Exception {
        ObjectNode bodyNode = mapper.createObjectNode();
        bodyNode.put("model", modelId);
        bodyNode.put("temperature", 0.0);
        bodyNode.set("usage", mapper.valueToTree(Map.of("include", true)));
        if (supportsReasoningEffort(modelId)) {
            bodyNode.putObject("reasoning").put("effort", "medium");
        }
        bodyNode.set("messages", mapper.valueToTree(List.of(
                Map.of("role", "system", "content", modelRequest.getSystemPrompt()),
                Map.of("role", "user", "content", modelRequest.getUserPrompt()))));

        System.out.println("Sending request to openrouter on model " + modelId);
        
        HttpResponse<String> response;
        try {
            response = httpClient.send(buildCompletionRequest(bodyNode), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("OpenRouter request failed for task [" + modelRequest.getRequestId() + "]: "
                    + summarizeBody(e.getMessage()), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenRouter request failed for task [" + modelRequest.getRequestId()
                    + "] with status " + response.statusCode() + ": " + summarizeBody(response.body()));
        }

        ObjectNode rowNode = buildResultRow(modelRequest.getRequestId(), response.statusCode(), response.body());
        return new RequestOutcome(parseResultRow(rowNode), mapper.writeValueAsString(rowNode));
    }

    private ObjectNode buildResultRow(String requestId, int statusCode, String responseBody) throws Exception {
        ObjectNode rowNode = mapper.createObjectNode();
        rowNode.put("custom_id", requestId);
        ObjectNode responseNode = rowNode.putObject("response");
        responseNode.put("status_code", statusCode);
        responseNode.set("body", mapper.readTree(responseBody));
        return rowNode;
    }

    private ModelResponse parseResultRow(JsonNode root) {
        String customId = root.path("custom_id").asText();
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            throw new IllegalStateException("OpenRouter batch output contains a failed task [" + customId + "]: "
                    + summarizeBody(errorNode.toString()));
        }

        JsonNode responseNode = root.path("response");
        int statusCode = responseNode.path("status_code").asInt(0);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("OpenRouter batch output contains a non-success task [" + customId
                    + "] with status " + statusCode + ": "
                    + summarizeBody(responseNode.path("body").toString()));
        }

        JsonNode responseBody = responseNode.path("body");
        JsonNode choicesNode = responseBody.path("choices");
        if (!choicesNode.isArray() || choicesNode.isEmpty()) {
            throw new IllegalStateException("OpenRouter batch output for task [" + customId
                    + "] is missing completion choices: " + summarizeBody(responseBody.toString()));
        }

        String content = extractChoiceContent(choicesNode.get(0));
        if (content.isBlank()) {
            System.err.println("WARNING: OpenRouter response content was empty for task [" + customId
                    + "]. Persisting raw response body for downstream inspection.");
            content = responseBody.toString();
        }

        int promptTokens = responseBody.path("usage").path("prompt_tokens").asInt();
        int completionTokens = responseBody.path("usage").path("completion_tokens").asInt();
        return new ModelResponse(customId, content, promptTokens, completionTokens);
    }

    private String extractChoiceContent(JsonNode choiceNode) {
        JsonNode messageNode = choiceNode.path("message");
        String content = extractTextPayload(messageNode.path("content"));
        if (!content.isBlank()) {
            return content;
        }

        content = extractTextPayload(choiceNode.path("text"));
        if (!content.isBlank()) {
            return content;
        }

        content = extractTextPayload(messageNode.path("output_text"));
        if (!content.isBlank()) {
            return content;
        }

        content = extractTextPayload(messageNode.path("reasoning"));
        return content;
    }

    private String extractTextPayload(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        if (node.isTextual()) {
            return node.asText();
        }

        if (node.isArray()) {
            StringBuilder combined = new StringBuilder();
            for (JsonNode child : node) {
                appendText(combined, extractTextPayload(child));
            }
            return combined.toString().trim();
        }

        if (node.isObject()) {
            StringBuilder combined = new StringBuilder();
            appendText(combined, extractTextPayload(node.path("text")));
            appendText(combined, extractTextPayload(node.path("content")));
            appendText(combined, extractTextPayload(node.path("output_text")));
            appendText(combined, extractTextPayload(node.path("parts")));
            appendText(combined, extractTextPayload(node.path("value")));

            if (combined.length() > 0) {
                return combined.toString().trim();
            }

            node.fields().forEachRemaining(field -> {
                if (field.getValue().isTextual()) {
                    appendText(combined, field.getValue().asText());
                }
            });

            return combined.toString().trim();
        }

        return "";
    }

    private void appendText(StringBuilder target, String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(chunk.trim());
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
            JsonNode pricingNode = findPricingEntry(root.path("data"), modelId);
            if (pricingNode != null) {
                JsonNode pricing = pricingNode.path("pricing");
                promptCostPerToken = parseDecimal(pricing.path("prompt").asText());
                completionCostPerToken = parseDecimal(pricing.path("completion").asText());
                return;
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

    static boolean supportsReasoningEffort(String candidateModelId) {
        if (candidateModelId == null) {
            return false;
        }

        String normalized = normalizeModelRef(candidateModelId);
        return normalized.startsWith("openai/gpt-5");
    }

    static JsonNode findPricingEntry(JsonNode models, String requestedModelId) {
        if (models == null || !models.isArray() || requestedModelId == null || requestedModelId.isBlank()) {
            return null;
        }

        String requested = normalizeModelRef(requestedModelId);
        List<ModelMatchVariant> requestedVariants = requestedVariants(requested);

        JsonNode bestNode = null;
        int bestScore = Integer.MAX_VALUE;

        for (JsonNode modelNode : models) {
            String id = normalizeModelRef(modelNode.path("id").asText(""));
            String canonical = normalizeModelRef(modelNode.path("canonical_slug").asText(""));
            int score = Math.min(
                    scorePricingMatch(requestedVariants, id),
                    scorePricingMatch(requestedVariants, canonical));
            if (score < bestScore) {
                bestScore = score;
                bestNode = modelNode;
            }
        }

        return bestScore == Integer.MAX_VALUE ? null : bestNode;
    }

    private static int scorePricingMatch(List<ModelMatchVariant> requestedVariants, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Integer.MAX_VALUE;
        }

        int bestScore = Integer.MAX_VALUE;
        String candidateBase = stripDatedSuffix(candidate);

        for (ModelMatchVariant variant : requestedVariants) {
            String requested = variant.value();
            String requestedBase = stripDatedSuffix(requested);
            int score = Integer.MAX_VALUE;

            if (candidate.equals(requested)) {
                score = 0;
            } else if (candidateBase.equals(requestedBase)) {
                score = 10;
            } else if (candidate.startsWith(requested + "-")) {
                score = 20 + (candidate.length() - requested.length());
            } else if (candidateBase.startsWith(requestedBase + "-")) {
                score = 40 + (candidateBase.length() - requestedBase.length());
            }

            if (score != Integer.MAX_VALUE) {
                score += variant.penalty();
                if (score < bestScore) {
                    bestScore = score;
                }
            }
        }

        return bestScore;
    }

    private static String normalizeModelRef(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int variantSeparator = normalized.indexOf(':');
        if (variantSeparator > 0) {
            normalized = normalized.substring(0, variantSeparator);
        }
        return normalized;
    }

    private static String stripDatedSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceFirst("-\\d{8}$", "")
                .replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "");
    }

    private static List<ModelMatchVariant> requestedVariants(String requested) {
        LinkedHashMap<String, Integer> variants = new LinkedHashMap<>();
        addVariant(variants, requested, 0);

        String stripped = stripDatedSuffix(requested);
        addVariant(variants, stripped, 5);

        String collapsed = collapseOpenAiGpt5Version(stripped);
        if (!collapsed.equals(stripped)) {
            addVariant(variants, collapsed, 35);
        }

        return variants.entrySet().stream()
                .map(entry -> new ModelMatchVariant(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void addVariant(LinkedHashMap<String, Integer> variants, String value, int penalty) {
        if (value == null || value.isBlank()) {
            return;
        }

        variants.merge(value, penalty, Math::min);
    }

    private static String collapseOpenAiGpt5Version(String value) {
        return value
                .replaceFirst("^openai/gpt-5\\.[12]-mini$", "openai/gpt-5-mini")
                .replaceFirst("^openai/gpt-5\\.[12]$", "openai/gpt-5");
    }

    private Exception toException(String message, Throwable error) {
        if (error instanceof Exception exception) {
            return new Exception(message + ": " + exception.getMessage(), exception);
        }
        return new Exception(message + ": " + error.getMessage(), error);
    }

    private String summarizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response body)";
        }

        String normalized = body.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    private static final class GlobalRateGate {
        private final long intervalNanos;
        private final AtomicLong next = new AtomicLong(0);
        private final boolean enabled;

        GlobalRateGate(int requestsPerMinute) {
            if (requestsPerMinute <= 0) {
                this.enabled = false;
                this.intervalNanos = 0;
                return;
            }

            this.enabled = true;
            this.intervalNanos = Duration.ofMinutes(1).toNanos() / requestsPerMinute;
        }

        void acquire() {
            if (!enabled) {
                return;
            }

            while (true) {
                long now = System.nanoTime();
                long prev = next.get();
                long startAt = Math.max(now, prev);
                long newNext = startAt + intervalNanos;
                if (next.compareAndSet(prev, newNext)) {
                    long wait = startAt - now;
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    return;
                }
            }
        }
    }

    private record RequestOutcome(ModelResponse response, String resultLine) {
    }

    private record ModelMatchVariant(String value, int penalty) {
    }
}
