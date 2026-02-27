package us.poliscore.polibench;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
import com.fasterxml.jackson.core.type.TypeReference;

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

    @Option(names = { "-m",
            "--model" }, description = "The model to evaluate (e.g., gpt-4o, gpt-4o-mini)", defaultValue = "gpt-4o-mini")
    private String modelId;

    @Option(names = {
            "--suites" }, description = "Directory containing the test suite JSON files (defaults to internal classpath resources)")
    private File suitesDir;

    @Option(names = { "-o",
            "--output" }, description = "Output path for the final polibench_results.json", defaultValue = "polibench_results.json")
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

            // Load suites
            List<TestSuite> allSuites = loadTestSuites(mapper);
            if (allSuites.isEmpty()) {
                System.err.println("No test suites found! Exiting.");
                return;
            }

            List<ModelRequest> allRequests = new ArrayList<>();
            Map<String, Task> taskMap = new java.util.HashMap<>();
            int totalExpectedOutputTokens = 0;
            int totalInputTokens = 0;

            // Generate requests from tasks
            for (TestSuite suite : allSuites) {
                System.out.println("Loaded Suite: " + suite.getName() + " (" + suite.getTasks().size() + " tasks)");
                for (Task task : suite.getTasks()) {
                    String reqId = (task.getId() != null && !task.getId().isEmpty()) ? task.getId()
                            : UUID.randomUUID().toString();
                    task.setId(reqId);
                    taskMap.put(reqId, task);

                    String systemPrompt = "You are an expert policy analyst and evaluator acting on behalf of a non-partisan oversight committee. "
                            +
                            task.getRequirement();

                    int promptTokens = CostEstimator.estimateTokens(systemPrompt + " " + task.getPrompt());
                    totalInputTokens += promptTokens;

                    // We assume a short paragraph answer to evaluate, ~150 words/tokens estimated
                    totalExpectedOutputTokens += 150;

                    allRequests.add(new ModelRequest(reqId, systemPrompt, task.getPrompt()));
                }
            }

            List<ModelResponse> responses;

            if (existingBatchResult != null && existingBatchResult.exists()) {
                System.out.println("Parsing existing batch results from: " + existingBatchResult.getAbsolutePath());
                responses = provider.parseBatchResults(existingBatchResult.getAbsolutePath());
            } else {
                // Determine Cost
                double estimatedCost = provider.calculateEstimatedCost(totalInputTokens, totalExpectedOutputTokens);
                System.out.printf("\n--- COST ESTIMATION ---\n");
                System.out.printf("Total Input Tokens (est):  %d\n", totalInputTokens);
                System.out.printf("Total Output Tokens (est): %d\n", totalExpectedOutputTokens);
                System.out.printf("Estimated Batch Cost:      $%.4f\n\n", estimatedCost);

                if (!autoAccept) {
                    System.out.print("This will require " + allRequests.size() + " requests to " + modelId +
                            " and will cost an estimated $" + String.format("%.2f", estimatedCost)
                            + ". Do you accept? (y/N): ");
                    Scanner scanner = new Scanner(System.in);
                    String answer = scanner.nextLine().trim().toLowerCase();
                    if (!answer.equals("y") && !answer.equals("yes")) {
                        System.out.println("Aborting.");
                        return;
                    }
                }

                // Generate Batch File
                String batchFileName = "polibench_batch_input_" + System.currentTimeMillis() + ".jsonl";
                provider.generateBatchFile(allRequests, batchFileName);
                System.out.println("Generated batch input file: " + batchFileName);

                System.out.println(
                        "\n[Action Required] The batch file is ready. For the prototype, you must manually upload this file to the OpenAI Batch API.");
                System.out.println(
                        "Once the batch succeeds and you download the output JSONL file, re-run this tool with: ");
                System.out.println(
                        "  java -jar target/polibench-1.0-SNAPSHOT.jar --results-only <path_to_downloaded_jsonl>");
                return;
            }

            // Evaluation Phase
            System.out.println("\nStarting Evaluation Engine...");
            BenchmarkEvaluator evaluator = new BenchmarkEvaluator();
            BenchmarkResult finalResult = new BenchmarkResult(modelId, java.time.Instant.now().toString());

            Map<Pillar, List<Task>> tasksByPillar = allSuites.stream()
                    .flatMap(suite -> suite.getTasks().stream()
                            .map(t -> new java.util.AbstractMap.SimpleEntry<>(suite.getPillar(), t)))
                    .collect(Collectors.groupingBy(Map.Entry::getKey,
                            Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

            for (Map.Entry<Pillar, List<Task>> entry : tasksByPillar.entrySet()) {
                Pillar pillar = entry.getKey();
                int totalPillarTasks = entry.getValue().size();
                int passedTasks = 0;

                for (Task task : entry.getValue()) {
                    ModelResponse resp = responses.stream()
                            .filter(r -> r.getRequestId().equals(task.getId()))
                            .findFirst()
                            .orElse(null);

                    if (resp != null && evaluator.evaluate(task, resp)) {
                        passedTasks++;
                    } else if (resp == null) {
                        System.err.println("WARNING: Missing response for task ID: " + task.getId());
                    }
                }

                finalResult.getPillarScores().put(pillar,
                        new BenchmarkResult.PillarResult(totalPillarTasks, passedTasks));
            }

            // Write final results
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, finalResult);
            System.out.println("\nSuccessfully generated evaluation results: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Fatal error during execution: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private AiProvider getProvider(String modelId) {
        if (modelId.startsWith("gpt-")) {
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
            String[] defaultSuites = { "precision.json", "evidence.json", "feasibility.json" };
            for (String sd : defaultSuites) {
                InputStream is = getClass().getResourceAsStream("/suites/" + sd);
                if (is != null) {
                    suites.add(mapper.readValue(is, TestSuite.class));
                }
            }
        }
        return suites;
    }
}
