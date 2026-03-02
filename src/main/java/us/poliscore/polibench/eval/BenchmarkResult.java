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

        public int getTotalTasks() {
            return totalTasks;
        }

        public int getPassedTasks() {
            return passedTasks;
        }

        public double getScorePercentage() {
            return scorePercentage;
        }

        public java.util.List<TaskResult> getTasks() {
            return tasks;
        }

        public void setTasks(java.util.List<TaskResult> tasks) {
            this.tasks = tasks;
        }
    }

    public static class TaskResult {
        private String id;
        private String requirement;
        private String prompt;
        private String systemPrompt;
        private String expected;
        private String response;
        private boolean passed;

        public TaskResult() {
        }

        public TaskResult(String id, String requirement, String prompt, String systemPrompt, String expected,
                String response, boolean passed) {
            this.id = id;
            this.requirement = requirement;
            this.prompt = prompt;
            this.systemPrompt = systemPrompt;
            this.expected = expected;
            this.response = response;
            this.passed = passed;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getRequirement() {
            return requirement;
        }

        public void setRequirement(String requirement) {
            this.requirement = requirement;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public String getExpected() {
            return expected;
        }

        public void setExpected(String expected) {
            this.expected = expected;
        }

        public String getResponse() {
            return response;
        }

        public void setResponse(String response) {
            this.response = response;
        }

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }
    }
}
