package us.poliscore.polibench;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import us.poliscore.model.BillPrompt;
import us.poliscore.polibench.eval.BenchmarkEvaluator;
import us.poliscore.polibench.eval.BenchmarkResult;
import us.poliscore.polibench.eval.CostEstimator;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.Task;
import us.poliscore.polibench.models.TestSuite;
import us.poliscore.polibench.providers.AiProvider;
import us.poliscore.polibench.providers.OpenAIProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;

@Command(name = "polibench", mixinStandardHelpOptions = true, version = "1.0", description = "Runs the PoliBench evaluation suite against an AI model via batch API.")
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
            "--model" }, description = "The model to evaluate (e.g., gpt-5-mini, gpt-4o-mini, mock)", defaultValue = "gpt-5-mini")
    private String modelId;

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
            System.out.println("Starting PoliBench Pipeline for model: " + modelId);
            AiProvider provider = getProvider(modelId);
            ObjectMapper mapper = new ObjectMapper();

            // Step 1: Load Test Suites
            List<TestSuite> allSuites = loadTestSuites(mapper);
            if (allSuites.isEmpty()) {
                System.err.println("No test suites found! Exiting.");
                return;
            }

            // Step 2: Generate Requests & Calculate Tokens
            PipelineContext context = buildRequestsContext(allSuites);

            List<ModelResponse> responses;

            if (existingBatchResult != null && existingBatchResult.exists()) {
                System.out.println("Parsing existing batch results from: " + existingBatchResult.getAbsolutePath());
                responses = provider.parseBatchResults(existingBatchResult.getAbsolutePath());
            } else {
                // Step 3: Confirm Execution Cost
                if (!confirmExecutionCost(provider, context)) {
                    System.out.println("Aborting.");
                    return;
                }

                // Step 4: Execute Batch Pipeline
                responses = executeBatchPipeline(provider, context.requests);
            }

            // Step 5: Evaluate Results
            evaluateResults(mapper, allSuites, responses);

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

    private PipelineContext buildRequestsContext(List<TestSuite> allSuites) {
        PipelineContext context = new PipelineContext();
        for (TestSuite suite : allSuites) {
            System.out.println("Loaded Suite: " + suite.getName() + " (" + suite.getTasks().size() + " tasks)");
            for (Task task : suite.getTasks()) {
                String systemPrompt = BillPrompt.getPromptForBill(false, false);
                String billText = task.getBillText();

                int promptTokens = CostEstimator.estimateTokens(systemPrompt + " " + billText);
                context.totalInputTokens += promptTokens;
                context.totalExpectedOutputTokens += 150;

                context.requests.add(new ModelRequest(UUID.randomUUID().toString(), systemPrompt, billText));
            }
        }
        return context;
    }

    @SuppressWarnings("resource")
    private boolean confirmExecutionCost(AiProvider provider, PipelineContext context) {
        // Skip prompt for mock (it's free anyway)
        if (modelId.equals("mock") || autoAccept) {
            return true;
        }

        double estimatedCost = provider.calculateEstimatedCost(context.totalInputTokens,
                context.totalExpectedOutputTokens);
        System.out.printf("\n--- COST ESTIMATION ---\n");
        System.out.printf("Total Input Tokens (est):  %d\n", context.totalInputTokens);
        System.out.printf("Total Output Tokens (est): %d\n", context.totalExpectedOutputTokens);
        System.out.printf("Estimated Batch Cost:      $%.4f\n\n", estimatedCost);

        System.out.print("This will require " + context.requests.size() + " requests to " + modelId +
                " and will cost an estimated $" + String.format("%.2f", estimatedCost)
                + ". Do you accept? (y/N): ");
        Scanner scanner = new Scanner(System.in);
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.equals("y") || answer.equals("yes");
    }

    private List<ModelResponse> executeBatchPipeline(AiProvider provider, List<ModelRequest> requests)
            throws Exception {
        File batchFile = new File("results", "polibench_batch_input_" + System.currentTimeMillis() + ".jsonl");
        ensureParentDirectoryExists(batchFile);
        provider.generateBatchFile(requests, batchFile.getPath());
        System.out.println("Generated batch input file: " + batchFile.getAbsolutePath());

        String batchId = provider.submitBatch(batchFile.getPath());
        System.out.println("Started batch execution with ID: " + batchId);

        boolean isComplete = false;
        while (!isComplete) {
            System.out.println("Polling batch status...");
            isComplete = provider.isBatchComplete(batchId);
            if (!isComplete) {
                if (!modelId.equals("mock")) {
                    Thread.sleep(30000);
                } else {
                    Thread.sleep(1000);
                }
            }
        }

        System.out.println("Batch execution complete. Fetching results...");
        return provider.fetchBatchResults(batchId);
    }

    private void evaluateResults(ObjectMapper mapper, List<TestSuite> allSuites, List<ModelResponse> responses)
            throws Exception {
        System.out.println("\nStarting Evaluation Engine...");
        BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
        BenchmarkResult finalResult = new BenchmarkResult(modelId, java.time.Instant.now().toString());
        finalResult.setSystemPrompt(BillPrompt.getPromptForBill(false, false));

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
                        resp != null ? resp.getContent() : null,
                        passed));
            }

            finalResult.getPillarScores().put(pillar,
                    new BenchmarkResult.PillarResult(totalPillarTasks, passedTasks, taskResults));
        }

        ensureParentDirectoryExists(outputFile);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, finalResult);
        System.out.println("\nSuccessfully generated evaluation results: " + outputFile.getAbsolutePath());
    }

    private AiProvider getProvider(String modelId) {
        if (modelId.equals("mock")) {
            return new us.poliscore.polibench.providers.MockProvider();
        } else if (modelId.startsWith("gpt-")) {
            return new OpenAIProvider(modelId);
        }
        throw new IllegalArgumentException("Unsupported model for provider resolution: " + modelId);
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
}
