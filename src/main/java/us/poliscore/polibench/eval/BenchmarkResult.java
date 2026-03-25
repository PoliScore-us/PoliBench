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
    }
}
