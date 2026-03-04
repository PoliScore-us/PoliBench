package us.poliscore.polibench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class AppModelSelectionTest {
    @Test
    void normalizeModelIdsTrimsDeduplicatesAndPreservesOrder() {
        List<String> normalized = App.normalizeModelIds(List.of(
                " mock ",
                "openai/gpt-5.2",
                "mock",
                " ",
                "anthropic/claude-sonnet-4"));

        assertEquals(List.of("mock", "openai/gpt-5.2", "anthropic/claude-sonnet-4"), normalized);
    }

    @Test
    void normalizeModelIdsFallsBackToMockWhenEmpty() {
        List<String> normalized = App.normalizeModelIds(List.of("", "  "));

        assertEquals(List.of("mock"), normalized);
    }
}
