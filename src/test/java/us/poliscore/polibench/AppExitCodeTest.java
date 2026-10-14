package us.poliscore.polibench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AppExitCodeTest {
    @TempDir Path directory;
    @Test void missingSuitesAndMissingResultsExitNonzero() {
        assertNotEquals(0, new CommandLine(new App()).execute("--suites", directory.resolve("missing").toString(), "--yes"));
        assertNotEquals(0, new CommandLine(new App()).execute("--results-only", directory.resolve("missing.jsonl").toString()));
    }
    @Test void descriptionIsOfflineAndIncludesEnforcedLimits() throws Exception {
        var output = directory.resolve("description.json");
        assertEquals(0, new CommandLine(new App()).execute("--describe", output.toString()));
        var description = new ObjectMapper().readTree(output.toFile());
        assertTrue(description.get("taskCount").asInt() > 0);
        assertEquals(BenchmarkDefinition.MAX_COMPLETION_TOKENS, description.get("maxCompletionTokens").asInt());
        assertEquals(BenchmarkDefinition.systemPrompt(), description.get("systemPrompt").asText());
    }
}
