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
            System.err.println("WARNING: Task [" + task.getId() + "] response could not be parsed by BillInterpretationParser: "
                    + e.getMessage());
            return false;
        }

        Boolean actualPass = interpretation.getStructuralAnalysisPassFail().get(toStructuralAnalysis(task.getPillar()));
        if (actualPass == null) {
            System.err.println(
                    "WARNING: Task [" + task.getId() + "] parsed response was missing structural analysis output for pillar "
                            + task.getPillar().getValue() + ".");
            return false;
        }

        return expectedOutcome.equals("PASS") == actualPass;
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
