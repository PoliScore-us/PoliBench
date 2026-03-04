package us.poliscore.polibench;

import java.io.File;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
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
import us.poliscore.model.bill.BillPrompt;
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

@Command(name = "polibench", mixinStandardHelpOptions = true, version = "1.0", description = "Runs the PoliBench evaluation suite against one or more AI models via batch API.")
public class App implements Runnable {
    static final DateTimeFormatter RUN_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
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
                System.err.println("No test suites found! Exiting.");
                return;
            }

            // Step 2: Generate Requests & Calculate Tokens
            PipelineContext context = buildRequestsContext(allSuites);

            List<BenchmarkResult> runResults = new ArrayList<>();

            if (existingBatchResult != null && existingBatchResult.exists()) {
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
                    return;
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

        } catch (Exception e) {
            System.err.println("Fatal error during execution: " + e.getMessage());
            e.printStackTrace();
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
                        ? UUID.randomUUID().toString()
                        : task.getId();
                task.setId(requestId);

                String systemPrompt = BillPrompt.getPromptForBill(false, false);
                String billText = task.getBillText();

                int promptTokens = CostEstimator.estimateTokens(systemPrompt + " " + billText);
                context.totalInputTokens += promptTokens;
                context.totalExpectedOutputTokens += 150;

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

    private BenchmarkResult evaluateResults(ObjectMapper mapper, List<TestSuite> allSuites, List<ModelResponse> responses,
            String modelId) {
        System.out.println("\nStarting Evaluation Engine...");
        BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
        BenchmarkResult finalResult = new BenchmarkResult(modelId);
        FailedResponseWriter failedResponseWriter = new FailedResponseWriter(mapper, modelId, resolveResultsDirectory());

        Map<Pillar, List<Task>> tasksByPillar = allSuites.stream()
                .flatMap(suite -> suite.getTasks().stream()
                        .map(t -> new java.util.AbstractMap.SimpleEntry<>(suite.getPillar(), t)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        try {
            for (Map.Entry<Pillar, List<Task>> entry : tasksByPillar.entrySet()) {
                Pillar pillar = entry.getKey();
                int totalPillarTasks = entry.getValue().size();
                int passedTasks = 0;
                List<BenchmarkResult.TaskResult> taskResults = new ArrayList<>();

                for (Task task : entry.getValue()) {
                    ModelResponse resp = responses.stream()
                            .filter(r -> r.getRequestId().equals(task.getId()))
                            .findFirst()
                            .orElse(null);

                    String billText = task.getBillText();

                    boolean passed = false;
                    String failureReason = "Missing response for task ID: " + task.getId();
                    if (resp != null) {
                        BenchmarkEvaluator.EvaluationOutcome outcome = evaluator.evaluateWithOutcome(task, resp);
                        passed = outcome.passed();
                        if (passed) {
                            passedTasks++;
                        } else {
                            failureReason = outcome.failureReason();
                        }
                    } else {
                        System.err.println("WARNING: " + failureReason);
                    }

                    if (!passed) {
                        failedResponseWriter.write(task, failureReason, resp);
                    }

                    taskResults.add(new BenchmarkResult.TaskResult(
                            task.getId(),
                            billText,
                            task.getExpected(),
                            task.getRationale(),
                            resp != null ? resp.getContent() : null,
                            passed));
                }

                finalResult.getPillarScores().put(pillar,
                        new BenchmarkResult.PillarResult(totalPillarTasks, passedTasks, taskResults));
            }
        } finally {
            failedResponseWriter.close();
        }
        return finalResult;
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
        String runDate = LocalDate.now(ZoneId.systemDefault()).format(RUN_DATE_FORMATTER);
        BenchmarkResultsArchive archive = mergeArchivesForDate(runDate,
                readExistingArchive(mapper),
                selectedModelIds,
                runResults);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, archive);
        System.out.println("\nSuccessfully generated evaluation results: " + outputFile.getAbsolutePath());
    }

    private BenchmarkResultsArchive readExistingArchive(ObjectMapper mapper) {
        if (outputFile == null || !outputFile.exists() || !outputFile.isFile()) {
            return null;
        }

        try {
            return mapper.readValue(outputFile, BenchmarkResultsArchive.class);
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
        if (suitesDir != null && suitesDir.exists() && suitesDir.isDirectory()) {
            File[] files = suitesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
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
                    System.err.println("WARNING: Missing bundled suite resource: " + sd);
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
            return new BenchmarkResultsArchive(runDate, new ArrayList<>(safeSelectedModels), BillPrompt.getPromptForBill(false, false), new ArrayList<>(safeRunResults));
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

        return new BenchmarkResultsArchive(runDate, new ArrayList<>(mergedModels), BillPrompt.getPromptForBill(false, false), mergedResults);
    }

    static String normalizeRunDate(String runDate) {
        if (runDate == null || runDate.isBlank()) {
            return "";
        }

        String trimmed = runDate.trim();
        try {
            return LocalDate.parse(trimmed, RUN_DATE_FORMATTER).format(RUN_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        try {
            return LocalDate.parse(trimmed).format(RUN_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        try {
            return Instant.parse(trimmed).atZone(ZoneId.systemDefault()).toLocalDate().format(RUN_DATE_FORMATTER);
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

        void write(Task task, String failureReason, ModelResponse response) {
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
