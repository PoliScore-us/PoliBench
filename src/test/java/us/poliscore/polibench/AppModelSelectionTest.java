package us.poliscore.polibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.poliscore.polibench.eval.BenchmarkResult;
import us.poliscore.polibench.eval.BenchmarkResultsArchive;
import us.poliscore.polibench.models.Pillar;

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

    @Test
    void mergeArchivesForDateAppendsWhenRunDateMatches() {
        BenchmarkResult existingResult = new BenchmarkResult("openai/gpt-5.1");
        BenchmarkResult newResult = new BenchmarkResult("openai/gpt-5.2");
        BenchmarkResultsArchive existingArchive = new BenchmarkResultsArchive(
                "2026-03-04",
                List.of("openai/gpt-5.1"),
                "prompt",
                List.of(existingResult));

        BenchmarkResultsArchive merged = App.mergeArchivesForDate("2026-03-04",
                existingArchive,
                List.of("openai/gpt-5.2"),
                List.of(newResult));

        assertEquals("2026-03-04", merged.getRunDate());
        assertEquals(List.of("openai/gpt-5.1", "openai/gpt-5.2"), merged.getModels());
        assertEquals(2, merged.getResults().size());
    }

    @Test
    void normalizeRunDateConvertsLegacyDatesToIso() {
        assertEquals("2026-03-25", App.normalizeRunDate("25/03/2026"));
        assertEquals("2026-03-25", App.normalizeRunDate("2026-03-25"));
        assertEquals("2026-03-25", App.normalizeRunDate("2026-03-25T12:00:00Z"));
    }

    @Test
    void benchmarkResultsArchiveRoundTripsThroughJackson() throws Exception {
        BenchmarkResult result = new BenchmarkResult("openai/gpt-5.1");
        result.setPillarScores(Map.of(Pillar.PRECISION, new BenchmarkResult.PillarResult(1, 1, new ArrayList<>())));
        BenchmarkResultsArchive archive = new BenchmarkResultsArchive("2026-03-04",
                List.of("openai/gpt-5.1"),
                "prompt",
                List.of(result));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(archive);
        BenchmarkResultsArchive parsed = mapper.readValue(json, BenchmarkResultsArchive.class);

        assertNotNull(parsed);
        assertEquals("2026-03-04", parsed.getRunDate());
        assertEquals(1, parsed.getResults().size());
        assertEquals("openai/gpt-5.1", parsed.getResults().get(0).getModelId());
    }
}
