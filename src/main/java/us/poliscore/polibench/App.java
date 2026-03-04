package us.poliscore.polibench;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;

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
                runResults.add(evaluateResults(allSuites, responses, modelId));
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
                    runResults.add(evaluateResults(allSuites, responses, runPlan.modelId()));
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

    private BenchmarkResult evaluateResults(List<TestSuite> allSuites, List<ModelResponse> responses, String modelId) {
        System.out.println("\nStarting Evaluation Engine...");
        BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
        BenchmarkResult finalResult = new BenchmarkResult(modelId, BillPrompt.getPromptForBill(false, false));

        Map<Pillar, List<Task>> tasksByPillar = allSuites.stream()
                .flatMap(suite -> suite.getTasks().stream()
                        .map(t -> new java.util.AbstractMap.SimpleEntry<>(suite.getPillar(), t)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

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
                if (resp != null && evaluator.evaluate(task, resp)) {
                    passedTasks++;
                    passed = true;
                } else if (resp == null) {
                    System.err.println("WARNING: Missing response for task ID: " + task.getId());
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

        return finalResult;
    }

    private void writeResultsArchive(ObjectMapper mapper, List<String> selectedModelIds, List<BenchmarkResult> runResults)
            throws Exception {
        ensureParentDirectoryExists(outputFile);
        BenchmarkResultsArchive archive = new BenchmarkResultsArchive(java.time.Instant.now().toString(),
                selectedModelIds,
                runResults);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, archive);
        System.out.println("\nSuccessfully generated evaluation results: " + outputFile.getAbsolutePath());
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
}
