package us.poliscore.polibench.eval;

import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import us.poliscore.polibench.models.Pillar;

/**
 * Represents the results of a PoliBench run, formatted for
 * ingestion by polibench-web.
 */
@Data
@RequiredArgsConstructor
@NoArgsConstructor
public class BenchmarkResult {
    @NonNull private String modelId;
    private boolean allNonparseable;
    private Metrics metrics;

    @Data @NoArgsConstructor
    public static class Metrics {
        private int totalTasks;
        private int readableJudgments;
        private int correctJudgments;
        private Double judgmentAccuracyPercentage;
        private int structurallyValidResponses;
        private double structuralReliabilityPercentage;
        private int completeReports;
        private double reportCompletionPercentage;
        private int incompleteGenerations;
        private int invalidFormats;
        private int wrongVerdicts;
        private int attempts;
        private long promptTokens;
        private long completionTokens;
        private java.math.BigDecimal reportedCostUsd = java.math.BigDecimal.ZERO;
        private boolean costAvailableForAllAttempts = true;
    }
    @NonNull private Map<Pillar, PillarResult> pillarScores = new HashMap<Pillar, PillarResult>();

    @Data
    @NoArgsConstructor
    public static class PillarResult {
        private int totalTasks;
        private int passedTasks;
        private double scorePercentage;
        private java.util.List<TaskResult> tasks;

        public PillarResult(int totalTasks, int passedTasks) {
            this.totalTasks = totalTasks;
            this.passedTasks = passedTasks;
            this.scorePercentage = totalTasks > 0 ? (double) passedTasks / totalTasks * 100.0 : 0.0;
            this.tasks = new java.util.ArrayList<>();
        }

        public PillarResult(int totalTasks, int passedTasks, java.util.List<TaskResult> tasks) {
            this.totalTasks = totalTasks;
            this.passedTasks = passedTasks;
            this.scorePercentage = totalTasks > 0 ? (double) passedTasks / totalTasks * 100.0 : 0.0;
            this.tasks = tasks;
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TaskResult {
        private String id;
        private String billText;
        private String expected;
        private String rationale;
        private String response;
        private boolean passed;
        private String error;
        private String outcome;
        private Boolean actualVerdict;
        private Boolean judgmentCorrect;
        private boolean structurallyValid;
        private boolean reportComplete;
        private java.util.List<us.poliscore.polibench.models.ModelResponse.Attempt> attempts;

        public TaskResult(String id, String billText, String expected, String rationale, String response,
                          boolean passed, String error) {
            this.id = id; this.billText = billText; this.expected = expected; this.rationale = rationale;
            this.response = response; this.passed = passed; this.error = error;
        }
    }
}
