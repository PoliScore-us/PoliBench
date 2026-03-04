package us.poliscore.polibench.eval;

import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillInterpretationParser;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.Task;

public class BenchmarkEvaluator {

    public record EvaluationOutcome(boolean passed, String failureReason) {
        public static EvaluationOutcome pass() {
            return new EvaluationOutcome(true, null);
        }

        public static EvaluationOutcome fail(String reason) {
            return new EvaluationOutcome(false, reason);
        }
    }

    /**
     * Evaluates a model's response by parsing for the mandatory `<PASS>` or
     * `<FAIL>`
     * token concluding the analysis, and comparing it to the Task's explicitly
     * expected outcome.
     */
    public boolean evaluate(Task task, ModelResponse response) {
        return evaluateWithOutcome(task, response).passed();
    }

    public EvaluationOutcome evaluateWithOutcome(Task task, ModelResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()
                || response.getContent().startsWith("ERROR:")) {
            return EvaluationOutcome.fail("Response content was blank or marked as an error payload.");
        }

        if (task.getPillar() == null) {
            throw new IllegalStateException("Task [" + task.getId() + "] is missing its pillar assignment.");
        }

        String expectedOutcome = task.getExpected().trim().toUpperCase();
        if (!expectedOutcome.equals("PASS") && !expectedOutcome.equals("FAIL")) {
            throw new IllegalStateException("Task [" + task.getId() + "] has invalid expected outcome: "
                    + expectedOutcome + ". Must be 'PASS' or 'FAIL'.");
        }

        BillInterpretation interpretation;
        try {
            interpretation = parseInterpretation(task, response);
        } catch (Exception e) {
            String reason = "Response could not be parsed by BillInterpretationParser: " + e.getMessage();
            System.err.println("WARNING: Task [" + task.getId() + "] " + reason);
            return EvaluationOutcome.fail(reason);
        }

        Boolean actualPass = interpretation.getStructuralAnalysisPassFail().get(toStructuralAnalysis(task.getPillar()));
        if (actualPass == null) {
            String reason = "Parsed response was missing structural analysis output for pillar "
                    + task.getPillar().getValue() + ".";
            System.err.println("WARNING: Task [" + task.getId() + "] " + reason);
            return EvaluationOutcome.fail(reason);
        }

        boolean expectedPass = expectedOutcome.equals("PASS");
        if (expectedPass == actualPass) {
            return EvaluationOutcome.pass();
        }

        return EvaluationOutcome.fail("Expected " + expectedOutcome + " but model output resolved to "
                + (actualPass ? "PASS" : "FAIL") + ".");
    }

    private BillInterpretation parseInterpretation(Task task, ModelResponse response) {
        Bill bill = new Bill();
        bill.setId("BIL/mock/" + task.getId());
        bill.setName("Mock Bill");
        bill.setOfficialUrl("https://example.invalid/bills/" + task.getId());

        BillInterpretation interpretation = new BillInterpretation();
        interpretation.setBill(bill);
        interpretation.setMetadata(AIInterpretationMetadata.construct("polibench", "benchmark-evaluator", 0, false));

        new BillInterpretationParser(bill, interpretation, null).parse(response.getContent(), null);
        return interpretation;
    }

    private StructuralAnalysis toStructuralAnalysis(Pillar pillar) {
        return switch (pillar) {
            case PRECISION -> StructuralAnalysis.PRECISION;
            case EVIDENCE -> StructuralAnalysis.EVIDENCE;
            case FEASIBILITY -> StructuralAnalysis.FEASIBILITY;
            case BUDGET -> StructuralAnalysis.BUDGET;
            case FAIRNESS -> StructuralAnalysis.FAIRNESS;
            case GOVERNANCE -> StructuralAnalysis.GOVERNANCE;
            case RISK -> StructuralAnalysis.RISK;
        };
    }
}
