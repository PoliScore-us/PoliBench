package us.poliscore.polibench.eval;

import us.poliscore.polibench.models.Pillar;

/**
 * Represents the final aggregated results of a PoliBench run, formatted for
 * ingestion by polibench-web.
 */
public class BenchmarkResult {
    private String modelId;
    private String runDate;
    private java.util.Map<Pillar, PillarResult> pillarScores;

    public BenchmarkResult(String modelId, String runDate) {
        this.modelId = modelId;
        this.runDate = runDate;
        this.pillarScores = new java.util.HashMap<>();
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getRunDate() {
        return runDate;
    }

    public void setRunDate(String runDate) {
        this.runDate = runDate;
    }

    public java.util.Map<Pillar, PillarResult> getPillarScores() {
        return pillarScores;
    }

    public void setPillarScores(java.util.Map<Pillar, PillarResult> pillarScores) {
        this.pillarScores = pillarScores;
    }

    public static class PillarResult {
        private int totalTasks;
        private int passedTasks;
        private double scorePercentage;

        public PillarResult(int totalTasks, int passedTasks) {
            this.totalTasks = totalTasks;
            this.passedTasks = passedTasks;
            this.scorePercentage = totalTasks > 0 ? (double) passedTasks / totalTasks * 100.0 : 0.0;
        }

        public int getTotalTasks() {
            return totalTasks;
        }

        public int getPassedTasks() {
            return passedTasks;
        }

        public double getScorePercentage() {
            return scorePercentage;
        }
    }
}
