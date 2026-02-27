package us.poliscore.polibench.eval;

import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Task;

public class BenchmarkEvaluator {

    /**
     * Very basic fuzzy grader for the prototype. It checks if the model's response
     * contains the expected keywords/phrases from the task criteria.
     * In a robust implementation, this would likely use a specialized
     * LLM-as-a-judge prompt.
     */
    public boolean evaluate(Task task, ModelResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()
                || response.getContent().startsWith("ERROR:")) {
            return false;
        }

        String expected = task.getExpected().toLowerCase();
        String actual = response.getContent().toLowerCase();

        // For this baseline, we're assuming the expected field has a comma-separated
        // list of concepts or keywords the model should have identified.
        String[] keywords = expected.split(";");
        int matchCount = 0;
        for (String kw : keywords) {
            if (actual.contains(kw.trim().toLowerCase())) {
                matchCount++;
            }
        }

        // Pass if it hits at least 33% of the core expected concepts (for prototype
        // relaxation)
        return matchCount > 0 && matchCount >= (keywords.length / 3.0);
    }
}
