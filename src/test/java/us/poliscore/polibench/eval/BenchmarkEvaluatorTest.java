package us.poliscore.polibench.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import us.poliscore.polibench.models.*;

class BenchmarkEvaluatorTest {
    private String response() {
        var text = new StringBuilder("**Structural Analysis:**\n");
        for (var pillar : Pillar.values()) text.append("**").append(pillar.ordinal() + 1).append(". ")
                .append(pillar.getValue()).append(":** Analysis. <PASS>\n");
        return text.append("Impact Analysis:\nUnrelated <FAIL> token.").toString();
    }
    private Task task() {
        var task = new Task();
        task.setId("task"); task.setPillar(Pillar.RISK); task.setExpected("PASS");
        return task;
    }
    @Test void gradesMarkdownHeadingsAndIsolatesLaterSections() {
        assertTrue(new BenchmarkEvaluator().evaluate(task(), new ModelResponse("task", response(), 1, 1)));
    }
    @Test void wrongVerdictIsAValidBenchmarkFailure() {
        var outcome = new BenchmarkEvaluator().evaluateWithOutcome(task(),
                new ModelResponse("task", response().replace("Risk:** Analysis. <PASS>", "Risk:** Analysis. <FAIL>"), 1, 1));
        assertFalse(outcome.passed()); assertFalse(outcome.parseFailure()); assertNull(outcome.error());
    }
    @Test void missingPillarsAndDuplicatePillarsAreParseFailures() {
        for (String content : new String[] {response().replace("**7. Risk:** Analysis. <PASS>", ""),
                response().replace("Impact Analysis:", "7. Risk: Duplicate. <FAIL>\nImpact Analysis:"), ""}) {
            assertTrue(new BenchmarkEvaluator().evaluateWithOutcome(task(), new ModelResponse("task", content, 1, 1)).parseFailure());
        }
    }
    @Test void lastVerdictWithinPillarWins() {
        assertTrue(new BenchmarkEvaluator().evaluate(task(), new ModelResponse("task",
                response().replace("Risk:** Analysis. <PASS>", "Risk:** First <FAIL>, revised <PASS>"), 1, 1)));
    }
}
