package us.poliscore.polibench.eval;

import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Task;

public class BenchmarkEvaluator {

    /**
     * Evaluates a model's response by parsing for the mandatory `<PASS>` or
     * `<FAIL>`
     * token concluding the analysis, and comparing it to the Task's explicitly
     * expected outcome.
     */
    public boolean evaluate(Task task, ModelResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()
                || response.getContent().startsWith("ERROR:")) {
            return false;
        }

        String expectedOutcome = task.getExpected().trim().toUpperCase();
        if (!expectedOutcome.equals("PASS") && !expectedOutcome.equals("FAIL")) {
            throw new IllegalStateException("Task [" + task.getId() + "] has invalid expected outcome: "
                    + expectedOutcome + ". Must be 'PASS' or 'FAIL'.");
        }

        String actual = response.getContent().toUpperCase();

        // Ensure the model actually provided a conclusive token
        boolean predictedPass = actual.contains("<PASS>");
        boolean predictedFail = actual.contains("<FAIL>");

        if (predictedPass && predictedFail) {
            System.err.println("WARNING: Task [" + task.getId()
                    + "] yielded an ambiguous response containing both <PASS> and <FAIL>.");
            return false;
        }

        if (!predictedPass && !predictedFail) {
            System.err.println(
                    "WARNING: Task [" + task.getId() + "] response missing a conclusive <PASS> or <FAIL> token.");
            return false;
        }

        if (expectedOutcome.equals("PASS") && predictedPass)
            return true;
        if (expectedOutcome.equals("FAIL") && predictedFail)
            return true;

        return false;
    }
}
