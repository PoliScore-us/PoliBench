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
    @Test void acceptsInlineSectionBodiesWithoutReadingLaterVerdicts() {
        String content = response().replace("**Structural Analysis:**\n", "**Structural Analysis:**  ")
                .replace("Impact Analysis:\n", "Impact Analysis: ");
        assertTrue(new BenchmarkEvaluator().evaluate(task(), new ModelResponse("task", content, 1, 1)));
    }
    @Test void colonlessHeadingStillRequiresItsOwnLine() {
        var evaluator = new BenchmarkEvaluator();
        assertTrue(evaluator.evaluate(task(), new ModelResponse("task",
                response().replace("**Structural Analysis:**", "**Structural Analysis**"), 1, 1)));
        assertFalse(evaluator.evaluate(task(), new ModelResponse("task",
                response().replace("**Structural Analysis:**\n", "Structural Analysis "), 1, 1)));
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
    @Test void conflictingVerdictsAreRejected() {
        assertTrue(new BenchmarkEvaluator().evaluateWithOutcome(task(), new ModelResponse("task",
                response().replace("Risk:** Analysis. <PASS>", "Risk:** First <FAIL>, revised <PASS>"), 1, 1)).parseFailure());
    }

    @Test void targetJudgmentSurvivesMissingUnrelatedPillars() {
        var t = task(); t.setPillar(Pillar.PRECISION);
        var r = new ModelResponse("task", "### Structural Analysis\n1) Precision: Sound targeting. <PASS>\n2) Evidence: unfinished", 1, 1);
        var evaluator = new BenchmarkEvaluator();
        assertEquals(Boolean.TRUE, evaluator.targetVerdict(t, r));
        assertFalse(evaluator.structurallyValid(r));
    }

    @Test void truncatedResponseCannotPassEvenWithAllVerdicts() {
        var r = new ModelResponse("task", response(), 1, 1);
        var attempt = new ModelResponse.Attempt(); attempt.setFinishReason("length"); r.getAttempts().add(attempt);
        var evaluator = new BenchmarkEvaluator();
        assertFalse(evaluator.evaluate(task(), r));
        assertEquals(Boolean.TRUE, evaluator.targetVerdict(task(), r));
        assertFalse(evaluator.reportComplete(r));
    }

    @Test void fullReportCompletionRequiresContentInEverySection() {
        String text = "Neutral Summary: Summary.\nBill Title: Title\nOther Names: []\nTopics: []\n"
                + response().replace("**", "") + "\nLong Report: Long.\nConfidence: 90\nImpact Stats: Values.\n"
                + "Rating: 20\nCasual Report: Casual.\nShort Report: Complete.";
        var evaluator = new BenchmarkEvaluator();
        assertTrue(evaluator.reportComplete(new ModelResponse("task", text, 1, 1)));
        assertFalse(evaluator.reportComplete(new ModelResponse("task", text.replace("Bill Title: Title", "Bill Title:"), 1, 1)));
    }
}
