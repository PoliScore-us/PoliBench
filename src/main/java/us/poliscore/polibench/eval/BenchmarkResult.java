package us.poliscore.polibench.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import us.poliscore.polibench.models.Pillar;

/**
 * Represents the final aggregated results of a PoliBench run, formatted for
 * ingestion by polibench-web.
 */
@Data
public class BenchmarkResult {
    private String modelId;
    private String runDate;
    private String systemPrompt;
    private java.util.Map<Pillar, PillarResult> pillarScores;

    public BenchmarkResult(String modelId, String runDate) {
        this.modelId = modelId;
        this.runDate = runDate;
        this.pillarScores = new java.util.HashMap<>();
    }

    @Data
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
    }
}
