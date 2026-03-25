package us.poliscore.polibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.poliscore.polibench.eval.BenchmarkResult;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.Task;
import us.poliscore.polibench.models.TestSuite;
import us.poliscore.polibench.providers.MockProvider;

class AppEvaluationResultsTest {
    @TempDir
    Path tempDir;

    @Test
    void evaluateResultsCapturesTaskErrorsAndFlagsFullyNonparseableRuns() throws Exception {
        Task firstTask = createTask("task-1", "Bill text 1", "PASS", "Rationale 1", Pillar.PRECISION);
        Task secondTask = createTask("task-2", "Bill text 2", "FAIL", "Rationale 2", Pillar.PRECISION);

        TestSuite suite = new TestSuite();
        suite.setName("Precision parser regression");
        suite.setPillar(Pillar.PRECISION);
        suite.setTasks(List.of(firstTask, secondTask));

        List<ModelResponse> responses = List.of(
                new ModelResponse("task-1", "this is not parseable", 12, 8),
                new ModelResponse("task-2", "still not parseable", 10, 6));

        App app = new App();
        setPrivateField(app, "outputFile", tempDir.resolve("polibench_results.json").toFile());

        BenchmarkResult result = app.evaluateResults(new ObjectMapper(), List.of(suite), responses, "deepseek/test");

        assertTrue(result.isAllNonparseable());

        BenchmarkResult.PillarResult pillarResult = result.getPillarScores().get(Pillar.PRECISION);
        assertEquals(2, pillarResult.getTasks().size());
        assertFalse(pillarResult.getTasks().get(0).isPassed());
        assertFalse(pillarResult.getTasks().get(1).isPassed());
        assertTrue(pillarResult.getTasks().stream().allMatch(task -> task.getError() != null && !task.getError().isBlank()));

        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"allNonparseable\":true"));
        assertTrue(json.contains("\"error\":"));
    }

    @Test
    void evaluateResultsDoesNotFlagRunWhenAnyTaskIsParseable() throws Exception {
        Task unparsableTask = createTask("task-bad", "Bill text bad", "PASS", "Bad rationale", Pillar.PRECISION);
        Task parseableTask = createTask("task-good", "Bill text good", "PASS", "Good rationale", Pillar.EVIDENCE);

        TestSuite precisionSuite = new TestSuite();
        precisionSuite.setName("Precision parser regression");
        precisionSuite.setPillar(Pillar.PRECISION);
        precisionSuite.setTasks(List.of(unparsableTask));

        TestSuite evidenceSuite = new TestSuite();
        evidenceSuite.setName("Evidence mock parse");
        evidenceSuite.setPillar(Pillar.EVIDENCE);
        evidenceSuite.setTasks(List.of(parseableTask));

        ModelResponse parseableResponse = new MockProvider().executeRequests(List.of(
                new ModelRequest(parseableTask.getId(), "system", parseableTask.getBillText(), parseableTask)))
                .get(0);

        List<ModelResponse> responses = List.of(
                new ModelResponse(unparsableTask.getId(), "this is not parseable", 12, 8),
                parseableResponse);

        App app = new App();
        setPrivateField(app, "outputFile", tempDir.resolve("polibench_results.json").toFile());

        BenchmarkResult result = app.evaluateResults(new ObjectMapper(),
                List.of(precisionSuite, evidenceSuite),
                responses,
                "deepseek/test");

        assertFalse(result.isAllNonparseable());
    }

    private static Task createTask(String id, String billText, String expected, String rationale, Pillar pillar) {
        Task task = new Task();
        task.setId(id);
        task.setBillText(billText);
        task.setExpected(expected);
        task.setRationale(rationale);
        task.setPillar(pillar);
        return task;
    }

    private static void setPrivateField(Object target, String fieldName, File value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
