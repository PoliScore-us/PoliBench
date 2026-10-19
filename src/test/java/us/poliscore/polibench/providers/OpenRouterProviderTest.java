package us.poliscore.polibench.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.poliscore.polibench.models.ModelResponse;

class OpenRouterProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void selectsExplicitReasoningForGeminiAndCurrentGpt() {
        assertEquals("high", OpenRouterProvider.reasoningEffort("google/gemini-3.8-flash"));
        assertEquals("high", OpenRouterProvider.reasoningEffort("google/gemini-4-argon"));
        assertEquals("medium", OpenRouterProvider.reasoningEffort("openai/gpt-6.1-sol"));
        assertEquals("medium", OpenRouterProvider.reasoningEffort("openai/gpt-6-luna"));
        assertEquals(null, OpenRouterProvider.reasoningEffort("openai/gpt-4o"));
    }

    @Test
    void parseBatchResultsReturnsResponsesForSuccessfulRows() throws Exception {
        Path batchResults = tempDir.resolve("openrouter-success.jsonl");
        Files.writeString(batchResults, """
                {"custom_id":"task-1","response":{"status_code":200,"body":{"choices":[{"message":{"content":"Structured policy analysis"}}],"usage":{"prompt_tokens":123,"completion_tokens":456}}}}
                """);

        OpenRouterProvider provider = new OpenRouterProvider("openai/gpt-5.2");
        List<ModelResponse> responses = provider.parseBatchResults(batchResults.toString());

        assertEquals(1, responses.size());
        assertEquals("task-1", responses.get(0).getRequestId());
        assertEquals("Structured policy analysis", responses.get(0).getContent());
        assertEquals(123, responses.get(0).getPromptTokens());
        assertEquals(456, responses.get(0).getCompletionTokens());
    }

    @Test
    void parseBatchResultsExtractsStructuredArrayMessageContent() throws Exception {
        Path batchResults = tempDir.resolve("openrouter-structured-content.jsonl");
        Files.writeString(batchResults, """
                {"custom_id":"task-structured","response":{"status_code":200,"body":{"choices":[{"message":{"content":[{"type":"text","text":"Neutral Summary:\\nok"},{"type":"text","text":"Structural Analysis:\\n1. Precision: x <PASS>"}]}}],"usage":{"prompt_tokens":9,"completion_tokens":11}}}}
                """);

        OpenRouterProvider provider = new OpenRouterProvider("deepseek/deepseek-v3.2");
        List<ModelResponse> responses = provider.parseBatchResults(batchResults.toString());

        assertEquals(1, responses.size());
        assertEquals("task-structured", responses.get(0).getRequestId());
        assertTrue(responses.get(0).getContent().contains("Neutral Summary:"));
        assertTrue(responses.get(0).getContent().contains("Structural Analysis:"));
    }

    @Test
    void parseBatchResultsThrowsWhenProviderReturnedErrorRow() throws Exception {
        Path batchResults = tempDir.resolve("openrouter-error.jsonl");
        Files.writeString(batchResults, """
                {"custom_id":"task-2","error":{"status_code":402,"body":"{\\"error\\":{\\"message\\":\\"This request requires more credits\\"}}"}}
                """);

        OpenRouterProvider provider = new OpenRouterProvider("openai/gpt-5.2");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.parseBatchResults(batchResults.toString()));

        assertTrue(error.getMessage().contains("task [task-2]"));
        assertTrue(error.getMessage().contains("more credits"));
    }

    @Test
    void parseBatchResultsThrowsWhenResponseStatusIsNonSuccess() throws Exception {
        Path batchResults = tempDir.resolve("openrouter-non-success.jsonl");
        Files.writeString(batchResults, """
                {"custom_id":"task-3","response":{"status_code":429,"body":{"error":{"message":"Rate limited"}}}}
                """);

        OpenRouterProvider provider = new OpenRouterProvider("openai/gpt-5.2");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.parseBatchResults(batchResults.toString()));

        assertTrue(error.getMessage().contains("status 429"));
        assertTrue(error.getMessage().contains("task [task-3]"));
    }

    @Test
    void findPricingEntryMatchesDatedOpenRouterModelIdsForGptFiveMini() throws Exception {
        JsonNode data = MAPPER.readTree("""
                [
                  {"id":"openai/gpt-5.1-mini-2025-01-01","canonical_slug":"openai/gpt-5.1-mini","pricing":{"prompt":"0.000001","completion":"0.000002"}},
                  {"id":"openai/gpt-4o-mini","canonical_slug":"openai/gpt-4o-mini","pricing":{"prompt":"0.000001","completion":"0.000002"}}
                ]
                """);

        JsonNode pricingNode = OpenRouterProvider.findPricingEntry(data, "openai/gpt-5.1-mini");

        assertNotNull(pricingNode);
        assertEquals("openai/gpt-5.1-mini-2025-01-01", pricingNode.path("id").asText());
    }

    @Test
    void findPricingEntryMatchesDatedOpenRouterModelIdsForGptFivePointTwo() throws Exception {
        JsonNode data = MAPPER.readTree("""
                [
                  {"id":"openai/gpt-5.2-20260110","canonical_slug":"","pricing":{"prompt":"0.000001","completion":"0.000002"}},
                  {"id":"openai/gpt-5.1-20251113","canonical_slug":"","pricing":{"prompt":"0.000001","completion":"0.000002"}}
                ]
                """);

        JsonNode pricingNode = OpenRouterProvider.findPricingEntry(data, "openai/gpt-5.2");

        assertNotNull(pricingNode);
        assertEquals("openai/gpt-5.2-20260110", pricingNode.path("id").asText());
    }

    @Test
    void supportsReasoningEffortForOpenAiGptFiveModelsOnly() {
        assertTrue(OpenRouterProvider.supportsReasoningEffort("openai/gpt-5.1"));
        assertTrue(OpenRouterProvider.supportsReasoningEffort("openai/gpt-5.2-mini"));
        assertTrue(OpenRouterProvider.supportsReasoningEffort("openai/gpt-5.1-20251113"));
        assertFalse(OpenRouterProvider.supportsReasoningEffort("openai/gpt-4o"));
        assertFalse(OpenRouterProvider.supportsReasoningEffort("anthropic/claude-sonnet-4"));
    }

    @Test
    void findPricingEntryFallsBackToGptFiveMiniAliasWhenPointVersionUnavailable() throws Exception {
        JsonNode data = MAPPER.readTree("""
                [
                  {"id":"openai/gpt-5-mini","canonical_slug":"openai/gpt-5-mini","pricing":{"prompt":"0.000001","completion":"0.000002"}},
                  {"id":"openai/gpt-4o-mini","canonical_slug":"openai/gpt-4o-mini","pricing":{"prompt":"0.000001","completion":"0.000002"}}
                ]
                """);

        JsonNode pricingNode = OpenRouterProvider.findPricingEntry(data, "openai/gpt-5.1-mini");

        assertNotNull(pricingNode);
        assertEquals("openai/gpt-5-mini", pricingNode.path("id").asText());
    }

    @Test void emptyContentNeverFallsBackToReasoningAndKeepsUsage() throws Exception {
        Path file = tempDir.resolve("reasoning.jsonl");
        Files.writeString(file, """
            {"custom_id":"x","response":{"status_code":200,"body":{"id":"generation-x","provider":"Google","model":"google/gemini-3.8-flash","choices":[{"finish_reason":"length","native_finish_reason":"MAX_TOKENS","message":{"content":null,"reasoning":"Structural Analysis: fake answer"}}],"usage":{"prompt_tokens":10,"completion_tokens":100,"completion_tokens_details":{"reasoning_tokens":100},"cost":0.02}}}}
            """);
        var response = new OpenRouterProvider("google/gemini-3.8-flash").parseBatchResults(file.toString()).get(0);
        assertEquals("", response.getContent());
        assertTrue(response.isIncomplete()); assertTrue(OpenRouterProvider.shouldRetry(response));
        assertEquals(100, response.getAttempts().get(0).getReasoningTokens());
        assertEquals(0, response.getAttempts().get(0).getVisibleOutputTokens());
        assertEquals("Google", response.getAttempts().get(0).getProvider());
    }

    @Test void retriesOnlyExplicitTruncationAndReplaysBothAttempts() throws Exception {
        var calls = new java.util.ArrayList<JsonNode>();
        OpenRouterProvider provider = new OpenRouterProvider("google/gemini-3.8-flash") {
            @Override protected java.net.http.HttpResponse<String> sendCompletion(JsonNode body) {
                calls.add(body.deepCopy());
                String finish = calls.size() == 1 ? "length" : "stop";
                String payload = "{\"choices\":[{\"finish_reason\":\"" + finish
                    + "\",\"message\":{\"content\":\"Answer\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":100}}";
                return (java.net.http.HttpResponse<String>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{java.net.http.HttpResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "statusCode" -> 200;
                        case "body" -> payload;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            }
        };
        var request = new us.poliscore.polibench.models.ModelRequest("task", "system", "bill", null);
        var method = OpenRouterProvider.class.getDeclaredMethod("executeRequest", us.poliscore.polibench.models.ModelRequest.class);
        method.setAccessible(true);
        Object outcome = method.invoke(provider, request);
        var responseMethod = outcome.getClass().getDeclaredMethod("response"); responseMethod.setAccessible(true);
        var response = (ModelResponse) responseMethod.invoke(outcome);
        assertEquals(2, calls.size()); assertEquals(32768, calls.get(0).path("max_tokens").asInt());
        assertEquals(65536, calls.get(1).path("max_tokens").asInt());
        assertEquals(calls.get(0).path("reasoning"), calls.get(1).path("reasoning"));
        assertEquals(2, response.getAttempts().size()); assertEquals(200, response.getCompletionTokens());
        assertEquals(20, response.getPromptTokens()); assertFalse(OpenRouterProvider.shouldRetry(response));
        var rowMethod = outcome.getClass().getDeclaredMethod("resultLine"); rowMethod.setAccessible(true);
        Path file = tempDir.resolve("retry.jsonl"); Files.writeString(file, (String) rowMethod.invoke(outcome));
        var replay = provider.parseBatchResults(file.toString()).get(0);
        assertEquals(response.getAttempts(), replay.getAttempts());
        assertFalse(OpenRouterProvider.shouldRetry(new ModelResponse("x", "", 0, 0)));
    }

    @Test void retryIsBoundedAndMalformedStopDoesNotRetry() throws Exception {
        for (String finish : List.of("length", "stop", "content_filter")) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            OpenRouterProvider provider = new OpenRouterProvider("google/gemini-3.8-flash") {
                @Override protected java.net.http.HttpResponse<String> sendCompletion(JsonNode body) {
                    calls.incrementAndGet();
                    String payload = "{\"choices\":[{\"finish_reason\":\"" + finish
                        + "\",\"message\":{\"content\":\"\"}}],\"usage\":{}}";
                    return (java.net.http.HttpResponse<String>) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{java.net.http.HttpResponse.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "statusCode" -> 200;
                            case "body" -> payload;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
                }
            };
            var request = new us.poliscore.polibench.models.ModelRequest("x", "system", "bill", null);
            var method = OpenRouterProvider.class.getDeclaredMethod("executeRequest", us.poliscore.polibench.models.ModelRequest.class);
            method.setAccessible(true); method.invoke(provider, request);
            assertEquals(finish.equals("length") ? 2 : 1, calls.get());
        }
    }
}
