package us.poliscore.polibench;

import java.io.File;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import us.poliscore.polibench.eval.BenchmarkEvaluator;
import us.poliscore.polibench.eval.BenchmarkResult;
import us.poliscore.polibench.eval.BenchmarkResultsArchive;
import us.poliscore.polibench.eval.CostEstimator;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.Task;
import us.poliscore.polibench.models.TestSuite;
import us.poliscore.polibench.providers.AiProvider;
import us.poliscore.polibench.providers.OpenRouterProvider;

@Command(name = "polibench", mixinStandardHelpOptions = true, version = "1.0", description = "Runs the PoliBench evaluation suite against one or more AI models.")
public class App implements java.util.concurrent.Callable<Integer> {
    static final DateTimeFormatter RUN_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    static final DateTimeFormatter LEGACY_RUN_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String[] DEFAULT_SUITE_FILES = {
            "precision.json",
            "evidence.json",
            "feasibility.json",
            "budget.json",
            "fairness.json",
            "governance.json",
            "risk.json"
    };

    @Option(names = { "-m",
            "--model" }, split = ",", description = "One or more models to evaluate. Repeat the option or provide a comma-separated list (e.g., mock,openai/gpt-5.2)", defaultValue = "mock")
    private List<String> modelIds = new ArrayList<>();

    @Option(names = {
            "--suites" }, description = "Directory containing the test suite JSON files (defaults to internal classpath resources)")
    private File suitesDir;

    @Option(names = { "-o",
            "--output" }, description = "Output path for the final polibench_results.json", defaultValue = "results/polibench_results.json")
    private File outputFile;

    @Option(names = {
            "--results-only" }, description = "Skip generation/execution and just parse an existing batch output file")
    private File existingBatchResult;

    @Option(names = { "-y", "--yes" }, description = "Auto-accept cost estimations without prompting")
    private boolean autoAccept;

    @Option(names = "--describe", description = "Write benchmark request and token-limit metadata without calling a provider")
    private File descriptionFile;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            List<String> selectedModelIds = getSelectedModelIds();
            if (existingBatchResult != null && selectedModelIds.size() != 1) {
                throw new IllegalArgumentException("--results-only currently supports exactly one model");
            }

            System.out.println("Starting PoliBench Pipeline for models: " + String.join(", ", selectedModelIds));
            ObjectMapper mapper = JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                    .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                    .build();

            // Step 1: Load Test Suites
            List<TestSuite> allSuites = loadTestSuites(mapper);
            if (allSuites.isEmpty()) {
                throw new IllegalArgumentException("No test suites found");
            }

            // Step 2: Generate Requests & Calculate Tokens
            PipelineContext context = buildRequestsContext(allSuites);
            if (context.requests.isEmpty()) throw new IllegalArgumentException("Benchmark has no tasks");
            if (descriptionFile != null) {
                ensureParentDirectoryExists(descriptionFile);
                long inputTokenUpperBound = context.requests.stream().mapToLong(request ->
                        request.getSystemPrompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + request.getUserPrompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 128L).sum();
                mapper.writerWithDefaultPrettyPrinter().writeValue(descriptionFile, Map.of(
                        "taskCount", context.requests.size(),
                        "inputTokenUpperBound", inputTokenUpperBound,
                        "maxCompletionTokens", BenchmarkDefinition.MAX_COMPLETION_TOKENS,
                        "maxTotalCompletionTokens", BenchmarkDefinition.MAX_TOTAL_COMPLETION_TOKENS,
                        "maxAttempts", BenchmarkDefinition.MAX_ATTEMPTS,
                        "retryCompletionTokens", BenchmarkDefinition.RETRY_COMPLETION_TOKENS,
                        "generationPolicy", BenchmarkDefinition.GENERATION_POLICY,
                        "temperature", 0.0,
                        "reasoningEffort", "medium for numbered OpenAI GPT models; high for google/gemini-3.8-flash and google/gemini-4-argon",
                        "systemPrompt", BenchmarkDefinition.systemPrompt()));
                return 0;
            }

            List<BenchmarkResult> runResults = new ArrayList<>();

            if (existingBatchResult != null) {
                if (!existingBatchResult.isFile()) throw new IllegalArgumentException("Results file does not exist");
                String modelId = selectedModelIds.get(0);
                AiProvider provider = getProvider(modelId);
                System.out.println("Parsing existing batch results from: " + existingBatchResult.getAbsolutePath());
                List<ModelResponse> responses = provider.parseBatchResults(existingBatchResult.getAbsolutePath());
                runResults.add(evaluateResults(mapper, allSuites, responses, modelId));
            } else {
                List<ModelRunPlan> runPlans = selectedModelIds.stream()
                        .map(modelId -> new ModelRunPlan(modelId, getProvider(modelId)))
                        .toList();

                // Step 3: Confirm Execution Cost
                if (!confirmExecutionCost(runPlans, context)) {
                    System.out.println("Aborting.");
                    return 1;
                }

                // Step 4: Execute Batch Pipeline
                for (ModelRunPlan runPlan : runPlans) {
                    System.out.println("\n=== Running model: " + runPlan.modelId() + " ===");
                    List<ModelResponse> responses = executeBatchPipeline(runPlan.provider(), context.requests,
                            runPlan.modelId());
                    runResults.add(evaluateResults(mapper, allSuites, responses, runPlan.modelId()));
                }
            }

            // Step 5: Write Aggregate Results
            writeResultsArchive(mapper, selectedModelIds, runResults);
            return 0;

        } catch (Exception e) {
            System.err.println("Fatal error during execution: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private static class PipelineContext {
        List<ModelRequest> requests = new ArrayList<>();
        int totalInputTokens = 0;
        int totalExpectedOutputTokens = 0;
    }

    private record ModelRunPlan(String modelId, AiProvider provider) {
    }

    private PipelineContext buildRequestsContext(List<TestSuite> allSuites) {
        PipelineContext context = new PipelineContext();
        for (TestSuite suite : allSuites) {
            System.out.println("Loaded Suite: " + suite.getName() + " (" + suite.getTasks().size() + " tasks)");
            for (Task task : suite.getTasks()) {
                task.setPillar(suite.getPillar());
                String requestId = (task.getId() == null || task.getId().isBlank())
                        ? suite.getPillar().getValue() + "-" + context.requests.size()
                        : task.getId();
                if (context.requests.stream().anyMatch(request -> request.getRequestId().equals(requestId)))
                    throw new IllegalArgumentException("Duplicate task ID: " + requestId);
                if (task.getBillText() == null || task.getBillText().isBlank() || task.getExpected() == null
                        || !List.of("PASS", "FAIL").contains(task.getExpected().trim().toUpperCase()))
                    throw new IllegalArgumentException("Invalid benchmark task: " + requestId);
                task.setId(requestId);

                String systemPrompt = BenchmarkDefinition.systemPrompt();
                String billText = task.getBillText();

                int promptTokens = CostEstimator.estimateTokens(systemPrompt + " " + billText);
                context.totalInputTokens += promptTokens * BenchmarkDefinition.MAX_ATTEMPTS;
                context.totalExpectedOutputTokens += BenchmarkDefinition.MAX_TOTAL_COMPLETION_TOKENS;

                context.requests.add(new ModelRequest(requestId, systemPrompt, billText, task));
            }
        }
        return context;
    }

    @SuppressWarnings("resource")
    private boolean confirmExecutionCost(List<ModelRunPlan> runPlans, PipelineContext context) {
        if (autoAccept) {
            return true;
        }

        List<ModelRunPlan> billableRunPlans = runPlans.stream()
                .filter(runPlan -> !runPlan.modelId().equals("mock"))
                .toList();
        if (billableRunPlans.isEmpty()) {
            return true;
        }

        double estimatedCost = 0.0;
        for (ModelRunPlan runPlan : billableRunPlans) {
            estimatedCost += runPlan.provider().calculateEstimatedCost(context.totalInputTokens,
                    context.totalExpectedOutputTokens);
        }

        System.out.printf("\n--- COST ESTIMATION ---\n");
        System.out.printf("Per-Model Input Tokens (est):   %d\n", context.totalInputTokens);
        System.out.printf("Per-Model Output Tokens (est):  %d\n", context.totalExpectedOutputTokens);
        System.out.printf("Requests Per Model:             %d\n", context.requests.size());
        System.out.printf("Total Model Runs:               %d\n", runPlans.size());
        System.out.printf("Total Requests Across Models:   %d\n", context.requests.size() * runPlans.size());
        System.out.println("Estimated Costs:");
        for (ModelRunPlan runPlan : runPlans) {
            double modelCost = runPlan.modelId().equals("mock")
                    ? 0.0
                    : runPlan.provider().calculateEstimatedCost(context.totalInputTokens,
                            context.totalExpectedOutputTokens);
            System.out.printf("  %s: $%.4f\n", runPlan.modelId(), modelCost);
        }
        System.out.printf("Estimated Total Cost:           $%.4f\n\n", estimatedCost);

        System.out.print("This will execute PoliBench against " + runPlans.size() + " model(s). Continue? (y/N): ");
        Scanner scanner = new Scanner(System.in);
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.equals("y") || answer.equals("yes");
    }

    private List<ModelResponse> executeBatchPipeline(AiProvider provider, List<ModelRequest> requests, String modelId)
            throws Exception {
        System.out.println("Executing " + requests.size() + " requests in-memory for model: " + modelId);
        return provider.executeRequests(requests);
    }

    BenchmarkResult evaluateResults(ObjectMapper mapper, List<TestSuite> allSuites, List<ModelResponse> responses,
            String modelId) {
        System.out.println("\nStarting Evaluation Engine...");
        BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
        BenchmarkResult finalResult = new BenchmarkResult(modelId);
        FailedResponseWriter failedResponseWriter = new FailedResponseWriter(mapper, modelId, resolveResultsDirectory());
        Map<String, ModelResponse> responsesByRequestId = responses == null
                ? Map.of()
                : responses.stream()
                        .collect(Collectors.toMap(ModelResponse::getRequestId, response -> response, (left, right) -> left,
                                LinkedHashMap::new));
        Map<Pillar, BenchmarkResult.PillarResult> pillarResults = new LinkedHashMap<>();
        int totalTasks = 0;
        int totalParseFailures = 0;
        var metrics = new BenchmarkResult.Metrics();
        finalResult.setMetrics(metrics);

        try {
            for (TestSuite suite : allSuites) {
                Pillar pillar = suite.getPillar();
                BenchmarkResult.PillarResult pillarResult = pillarResults.computeIfAbsent(pillar,
                        ignored -> new BenchmarkResult.PillarResult(0, 0, new ArrayList<>()));
                int suiteTotalTasks = 0;
                int suitePassedTasks = 0;
                int suiteParseFailures = 0;

                for (Task task : suite.getTasks()) {
                    totalTasks++;
                    suiteTotalTasks++;
                    incrementTotalTasks(pillarResult);

                    ModelResponse resp = responsesByRequestId.get(task.getId());
                    String billText = task.getBillText();

                    boolean passed = false;
                    String failureReason = "Missing response for task ID: " + task.getId();
                    String error = null;
                    if (resp != null) {
                        BenchmarkEvaluator.EvaluationOutcome outcome = evaluator.evaluateWithOutcome(task, resp);
                        passed = outcome.passed();
                        if (passed) {
                            suitePassedTasks++;
                            incrementPassedTasks(pillarResult);
                        } else {
                            failureReason = outcome.failureReason();
                            error = outcome.error();
                            if (outcome.parseFailure()) {
                                totalParseFailures++;
                                suiteParseFailures++;
                            }
                        }
                    } else {
                        System.err.println("WARNING: " + failureReason);
                    }

                    if (!passed) {
                        failedResponseWriter.write(task, failureReason, error, resp);
                    }

                    var taskResult = new BenchmarkResult.TaskResult(
                            task.getId(),
                            billText,
                            task.getExpected(),
                            task.getRationale(),
                            resp != null ? resp.getContent() : null,
                            passed,
                            error);
                    Boolean verdict = evaluator.targetVerdict(task, resp);
                    Boolean judgmentCorrect = verdict == null ? null : verdict == task.getExpected().trim().equalsIgnoreCase("PASS");
                    boolean structural = evaluator.structurallyValid(resp);
                    boolean complete = evaluator.reportComplete(resp);
                    String outcome = resp == null ? "MISSING_RESPONSE" : resp.isIncomplete() ? "INCOMPLETE_GENERATION"
                            : !structural ? "INVALID_FORMAT" : !passed ? "WRONG_VERDICT" : "CORRECT";
                    taskResult.setOutcome(outcome);
                    taskResult.setActualVerdict(verdict);
                    taskResult.setJudgmentCorrect(judgmentCorrect);
                    taskResult.setStructurallyValid(structural);
                    taskResult.setReportComplete(complete);
                    taskResult.setAttempts(resp == null ? List.of() : resp.getAttempts());
                    pillarResult.getTasks().add(taskResult);
                    metrics.setTotalTasks(metrics.getTotalTasks() + 1);
                    if (verdict != null) metrics.setReadableJudgments(metrics.getReadableJudgments() + 1);
                    if (Boolean.TRUE.equals(judgmentCorrect)) metrics.setCorrectJudgments(metrics.getCorrectJudgments() + 1);
                    if (structural) metrics.setStructurallyValidResponses(metrics.getStructurallyValidResponses() + 1);
                    if (complete) metrics.setCompleteReports(metrics.getCompleteReports() + 1);
                    if (outcome.equals("INCOMPLETE_GENERATION") || outcome.equals("MISSING_RESPONSE"))
                        metrics.setIncompleteGenerations(metrics.getIncompleteGenerations() + 1);
                    if (outcome.equals("INVALID_FORMAT")) metrics.setInvalidFormats(metrics.getInvalidFormats() + 1);
                    if (outcome.equals("WRONG_VERDICT")) metrics.setWrongVerdicts(metrics.getWrongVerdicts() + 1);
                    if (resp != null) {
                        metrics.setPromptTokens(metrics.getPromptTokens() + resp.getPromptTokens());
                        metrics.setCompletionTokens(metrics.getCompletionTokens() + resp.getCompletionTokens());
                        metrics.setAttempts(metrics.getAttempts() + resp.getAttempts().size());
                        if (resp.getAttempts().isEmpty()) metrics.setCostAvailableForAllAttempts(false);
                        for (var attempt : resp.getAttempts()) {
                            if (attempt.getCostUsd() == null) metrics.setCostAvailableForAllAttempts(false);
                            else metrics.setReportedCostUsd(metrics.getReportedCostUsd().add(attempt.getCostUsd()));
                        }
                    } else metrics.setCostAvailableForAllAttempts(false);
                }

                recalculateScorePercentage(pillarResult);
            }
        } finally {
            failedResponseWriter.close();
        }
        if (totalTasks > 0) {
            metrics.setJudgmentAccuracyPercentage(metrics.getReadableJudgments() == 0 ? null
                    : 100.0 * metrics.getCorrectJudgments() / metrics.getReadableJudgments());
            metrics.setStructuralReliabilityPercentage(100.0 * metrics.getStructurallyValidResponses() / totalTasks);
            metrics.setReportCompletionPercentage(100.0 * metrics.getCompleteReports() / totalTasks);
        }
        finalResult.setPillarScores(pillarResults);
        finalResult.setAllNonparseable(totalTasks > 0 && totalParseFailures == totalTasks);
        return finalResult;
    }

    private static void incrementTotalTasks(BenchmarkResult.PillarResult pillarResult) {
        pillarResult.setTotalTasks(pillarResult.getTotalTasks() + 1);
    }

    private static void incrementPassedTasks(BenchmarkResult.PillarResult pillarResult) {
        pillarResult.setPassedTasks(pillarResult.getPassedTasks() + 1);
    }

    private static void recalculateScorePercentage(BenchmarkResult.PillarResult pillarResult) {
        pillarResult.setScorePercentage(
                pillarResult.getTotalTasks() > 0
                        ? (double) pillarResult.getPassedTasks() / pillarResult.getTotalTasks() * 100.0
                        : 0.0);
    }

    private Path resolveResultsDirectory() {
        File targetOutput = outputFile != null ? outputFile.getAbsoluteFile() : new File("results/polibench_results.json");
        File parentDir = targetOutput.getParentFile();
        if (parentDir != null) {
            return parentDir.toPath();
        }
        return Path.of("results");
    }

    private void writeResultsArchive(ObjectMapper mapper, List<String> selectedModelIds, List<BenchmarkResult> runResults)
            throws Exception {
        ensureParentDirectoryExists(outputFile);
        String runDate = LocalDate.now(ZoneId.of("UTC")).format(RUN_DATE_FORMATTER);
        BenchmarkResultsArchive archive = mergeArchivesForDate(runDate,
                readExistingArchive(mapper),
                selectedModelIds,
                runResults);
        com.fasterxml.jackson.databind.node.ObjectNode document = mapper.valueToTree(archive);
        document.put("evaluationVersion", "response-reliability-v2");
        if (existingBatchResult == null) document.set("generation", mapper.valueToTree(Map.of(
                "maxCompletionTokens", BenchmarkDefinition.MAX_COMPLETION_TOKENS,
                "retryCompletionTokens", BenchmarkDefinition.RETRY_COMPLETION_TOKENS,
                "maxAttempts", BenchmarkDefinition.MAX_ATTEMPTS,
                "maxTotalCompletionTokens", BenchmarkDefinition.MAX_TOTAL_COMPLETION_TOKENS,
                "temperature", 0.0, "generationPolicy", BenchmarkDefinition.GENERATION_POLICY,
                "reasoningEffort", "medium for numbered OpenAI GPT models; high for google/gemini-3.8-flash and google/gemini-4-argon; provider default otherwise")));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, document);
        System.out.println("\nSuccessfully generated evaluation results: " + outputFile.getAbsolutePath());
    }

    private BenchmarkResultsArchive readExistingArchive(ObjectMapper mapper) {
        if (outputFile == null || !outputFile.exists() || !outputFile.isFile()) {
            return null;
        }

        try {
            return mapper.readerFor(BenchmarkResultsArchive.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(outputFile);
        } catch (Exception e) {
            System.err.println("WARNING: Could not read existing results archive at " + outputFile.getAbsolutePath()
                    + ". A new archive will be written. Reason: " + e.getMessage());
            return null;
        }
    }

    private AiProvider getProvider(String modelId) {
        if (modelId.equals("mock")) {
            return new us.poliscore.polibench.providers.MockProvider();
        }
        return new OpenRouterProvider(modelId);
    }

    private List<TestSuite> loadTestSuites(ObjectMapper mapper) throws Exception {
        List<TestSuite> suites = new ArrayList<>();
        if (suitesDir != null) {
            if (!suitesDir.isDirectory()) throw new IllegalArgumentException("Suites directory does not exist");
            File[] files = suitesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                for (File f : files) {
                    suites.add(mapper.readValue(f, TestSuite.class));
                }
            }
        } else {
            // Load defaults from classpath
            for (String sd : DEFAULT_SUITE_FILES) {
                InputStream is = getClass().getResourceAsStream("/suites/" + sd);
                if (is != null) {
                    suites.add(mapper.readValue(is, TestSuite.class));
                } else {
                    throw new IllegalStateException("Missing bundled suite resource: " + sd);
                }
            }
        }
        return suites;
    }

    private void ensureParentDirectoryExists(File file) {
        File parentDir = file.getAbsoluteFile().getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    private List<String> getSelectedModelIds() {
        return normalizeModelIds(modelIds);
    }

    static List<String> normalizeModelIds(List<String> rawModelIds) {
        LinkedHashSet<String> normalizedModels = new LinkedHashSet<>();
        for (String rawModelId : rawModelIds) {
            if (rawModelId == null) {
                continue;
            }

            String normalizedModelId = rawModelId.trim();
            if (!normalizedModelId.isEmpty()) {
                normalizedModels.add(normalizedModelId);
            }
        }

        if (normalizedModels.isEmpty()) {
            normalizedModels.add("mock");
        }

        return new ArrayList<>(normalizedModels);
    }

    static BenchmarkResultsArchive mergeArchivesForDate(String runDate,
            BenchmarkResultsArchive existingArchive,
            List<String> selectedModelIds,
            List<BenchmarkResult> runResults) {
        List<String> safeSelectedModels = selectedModelIds == null ? List.of() : selectedModelIds;
        List<BenchmarkResult> safeRunResults = runResults == null ? List.of() : runResults;

        if (existingArchive == null || !runDate.equals(normalizeRunDate(existingArchive.getRunDate()))) {
            return new BenchmarkResultsArchive(runDate, new ArrayList<>(safeSelectedModels), BenchmarkDefinition.systemPrompt(), new ArrayList<>(safeRunResults));
        }

        LinkedHashSet<String> mergedModels = new LinkedHashSet<>();
        if (existingArchive.getModels() != null) {
            mergedModels.addAll(existingArchive.getModels());
        }
        mergedModels.addAll(safeSelectedModels);

        List<BenchmarkResult> mergedResults = new ArrayList<>();
        if (existingArchive.getResults() != null) {
            mergedResults.addAll(existingArchive.getResults());
        }
        mergedResults.addAll(safeRunResults);

        return new BenchmarkResultsArchive(runDate, new ArrayList<>(mergedModels), BenchmarkDefinition.systemPrompt(), mergedResults);
    }

    static String normalizeRunDate(String runDate) {
        if (runDate == null || runDate.isBlank()) {
            return "";
        }

        String trimmed = runDate.trim();
        try {
            return LocalDate.parse(trimmed, LEGACY_RUN_DATE_FORMATTER).format(RUN_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        try {
            return LocalDate.parse(trimmed).format(RUN_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        try {
            return Instant.parse(trimmed).atZone(ZoneId.of("UTC")).toLocalDate().format(RUN_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        return trimmed;
    }

    private static final class FailedResponseWriter {
        private final ObjectMapper mapper;
        private final String modelId;
        private final Path resultsDirectory;
        private BufferedWriter writer;
        private Path outputPath;
        private int failureCount;

        FailedResponseWriter(ObjectMapper mapper, String modelId, Path resultsDirectory) {
            this.mapper = mapper;
            this.modelId = modelId;
            this.resultsDirectory = resultsDirectory;
        }

        void write(Task task, String failureReason, String error, ModelResponse response) {
            try {
                if (writer == null) {
                    openWriter();
                }

                var row = mapper.createObjectNode();
                row.put("taskId", task.getId());
                row.put("modelId", modelId);
                row.put("pillar", task.getPillar() != null ? task.getPillar().getValue() : null);
                row.put("expected", task.getExpected());
                row.put("failureReason", failureReason != null ? failureReason : "Unknown evaluation failure");
                row.put("error", error);
                row.put("billText", task.getBillText());
                row.put("response", response != null ? response.getContent() : null);
                row.put("promptTokens", response != null ? response.getPromptTokens() : 0);
                row.put("completionTokens", response != null ? response.getCompletionTokens() : 0);

                writer.write(mapper.writeValueAsString(row));
                writer.newLine();
                writer.flush();
                failureCount++;
            } catch (Exception e) {
                System.err.println("WARNING: Failed to write evaluation failure record for task [" + task.getId()
                        + "]: " + e.getMessage());
            }
        }

        void close() {
            if (writer == null) {
                return;
            }

            try {
                writer.close();
                System.out.println("Wrote " + failureCount + " failed response(s) to: " + outputPath.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("WARNING: Failed to close failed response output file " + outputPath + ": "
                        + e.getMessage());
            } finally {
                writer = null;
            }
        }

        private void openWriter() throws IOException {
            Files.createDirectories(resultsDirectory);
            String sanitizedModel = sanitizeForFileName(modelId);
            outputPath = resultsDirectory
                    .resolve("openrouter_failed_output_" + sanitizedModel + "_" + UUID.randomUUID() + ".jsonl");
            writer = Files.newBufferedWriter(outputPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            System.out.println("Storing failed responses in: " + outputPath.toAbsolutePath());
        }

        private static String sanitizeForFileName(String input) {
            if (input == null || input.isBlank()) {
                return "model";
            }
            return input.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    }
}
