package us.poliscore.polibench.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.poliscore.polibench.models.ModelResponse;

class OpenRouterProviderTest {
    @TempDir
    Path tempDir;

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
}
