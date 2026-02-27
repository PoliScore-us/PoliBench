package us.poliscore.polibench.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class OpenAIProvider implements AiProvider {

    private final String modelId;
    private final double inputCostPerMillion;
    private final double outputCostPerMillion;
    private final String apiKey;
    private final HttpClient httpClient;

    public OpenAIProvider(String modelId) {
        this.modelId = modelId;
        this.apiKey = ConfigLoader.getProperty("openai.api.key");
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();

        switch (modelId) {
            case "gpt-5.2":
                this.inputCostPerMillion = 1.75;
                this.outputCostPerMillion = 14.00;
                break;
            case "gpt-5.1":
            case "gpt-5":
                this.inputCostPerMillion = 1.25;
                this.outputCostPerMillion = 10.00;
                break;
            case "gpt-5-mini":
                this.inputCostPerMillion = 0.25;
                this.outputCostPerMillion = 2.00;
                break;
            case "gpt-5-nano":
                this.inputCostPerMillion = 0.05;
                this.outputCostPerMillion = 0.40;
                break;
            case "gpt-4o":
                this.inputCostPerMillion = 5.00;
                this.outputCostPerMillion = 15.00;
                break;
            case "gpt-4o-mini":
                this.inputCostPerMillion = 0.15;
                this.outputCostPerMillion = 0.60;
                break;
            default:
                throw new IllegalArgumentException("Unknown OpenAI model: " + modelId);
        }
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public void generateBatchFile(java.util.List<us.poliscore.polibench.models.ModelRequest> requests,
            String batchFileOutputPath) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(batchFileOutputPath))) {
            for (us.poliscore.polibench.models.ModelRequest req : requests) {

                // Construct the messages array
                java.util.List<java.util.Map<String, String>> messages = new java.util.ArrayList<>();
                messages.add(java.util.Map.of("role", "system", "content", req.getSystemPrompt()));
                messages.add(java.util.Map.of("role", "user", "content", req.getUserPrompt()));

                // Construct the body of the API request
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", this.modelId);
                body.put("messages", messages);
                // Optional constraints we might want to enforce
                body.put("temperature", 0.0);

                // Construct the required JSONL line layout for OpenAI Batch endpoint
                java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
                batchRequest.put("custom_id", req.getRequestId());
                batchRequest.put("method", "POST");
                batchRequest.put("url", "/v1/chat/completions");
                batchRequest.put("body", body);

                out.println(mapper.writeValueAsString(batchRequest));
            }
        }
    }

    @Override
    public java.util.List<us.poliscore.polibench.models.ModelResponse> parseBatchResults(String batchResultInputPath)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<us.poliscore.polibench.models.ModelResponse> responses = new java.util.ArrayList<>();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(batchResultInputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(line);

                String customId = root.path("custom_id").asText();
                com.fasterxml.jackson.databind.JsonNode responseBody = root.path("response").path("body");

                String content = "";
                int promptTokens = 0;
                int completionTokens = 0;

                if (!responseBody.isMissingNode() && responseBody.has("choices")) {
                    content = responseBody.path("choices").get(0).path("message").path("content").asText();
                    promptTokens = responseBody.path("usage").path("prompt_tokens").asInt();
                    completionTokens = responseBody.path("usage").path("completion_tokens").asInt();
                } else {
                    // Handle API errors for this specific row
                    com.fasterxml.jackson.databind.JsonNode errorNode = root.path("error");
                    if (!errorNode.isMissingNode()) {
                        content = "ERROR: " + errorNode.toString();
                    }
                }

                responses.add(new us.poliscore.polibench.models.ModelResponse(customId, content, promptTokens,
                        completionTokens));
            }
        }
        return responses;
    }

    @Override
    public double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens) {
        // OpenAI batch discount is exactly 50%
        double batchDiscountMultiplier = 0.5;

        double inputCost = (promptTokens / 1_000_000.0) * this.inputCostPerMillion;
        double outputCost = (expectedCompletionTokens / 1_000_000.0) * this.outputCostPerMillion;

        return (inputCost + outputCost) * batchDiscountMultiplier;
    }

    @Override
    public String submitBatch(String batchFileOutputPath) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Missing openai.api.key in polibench.properties");
        }

        // 1. Upload the file
        System.out.println("Uploading batch file to OpenAI...");
        String fileId = uploadFile(batchFileOutputPath);
        System.out.println("File uploaded with ID: " + fileId);

        // 2. Submit the batch
        System.out.println("Submitting batch job...");
        String batchId = createBatchJob(fileId);
        System.out.println("Batch Job created with ID: " + batchId);

        return batchId;
    }

    private String uploadFile(String filePath) throws Exception {
        String boundary = "---boundary" + UUID.randomUUID().toString();
        byte[] fileBytes = Files.readAllBytes(Path.of(filePath));

        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n" +
                "batch\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"batch.jsonl\"\r\n" +
                "Content-Type: application/jsonl\r\n\r\n";

        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes();
        byte[] footerBytes = footer.getBytes();

        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        os.write(headerBytes);
        os.write(fileBytes);
        os.write(footerBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/files"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(os.toByteArray()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to upload file: " + response.body());
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(response.body());
        return rootNode.path("id").asText();
    }

    private String createBatchJob(String inputFileId) throws Exception {
        java.util.Map<String, Object> bodyMap = new java.util.HashMap<>();
        bodyMap.put("input_file_id", inputFileId);
        bodyMap.put("endpoint", "/v1/chat/completions");
        bodyMap.put("completion_window", "24h");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String jsonBody = mapper.writeValueAsString(bodyMap);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/batches"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to create batch: " + response.body());
        }

        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(response.body());
        return rootNode.path("id").asText();
    }

    @Override
    public boolean isBatchComplete(String batchId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/batches/" + batchId))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to check batch status: " + response.body());
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(response.body());
        String status = rootNode.path("status").asText();

        System.out.println("Batch [" + batchId + "] status: " + status);

        if ("completed".equals(status)) {
            return true;
        } else if ("failed".equals(status) || "expired".equals(status) || "cancelled".equals(status)) {
            throw new RuntimeException("Batch resulted in terminal state: " + status + ". Details: " + response.body());
        }

        return false;
    }

    @Override
    public java.util.List<us.poliscore.polibench.models.ModelResponse> fetchBatchResults(String batchId)
            throws Exception {
        // 1. Get the Output File ID from the Batch
        HttpRequest batchRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/batches/" + batchId))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> batchResponse = httpClient.send(batchRequest, HttpResponse.BodyHandlers.ofString());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode batchNode = mapper.readTree(batchResponse.body());

        String outputFileId = batchNode.path("output_file_id").asText();
        if (outputFileId == null || outputFileId.isEmpty() || "null".equals(outputFileId)) {
            throw new RuntimeException(
                    "Batch completed but no output_file_id is present. Details: " + batchResponse.body());
        }

        // 2. Download the actual file content
        System.out.println("Downloading output file: " + outputFileId);
        HttpRequest fileRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/files/" + outputFileId + "/content"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> fileResponse = httpClient.send(fileRequest, HttpResponse.BodyHandlers.ofString());

        if (fileResponse.statusCode() != 200) {
            throw new RuntimeException("Failed to download output file: " + fileResponse.body());
        }

        // 3. Save it to a temporary file, then use our existing parse method
        java.io.File tempFile = java.io.File.createTempFile("polibench_batch_output_", ".jsonl");
        Files.writeString(tempFile.toPath(), fileResponse.body());

        System.out.println("Successfully downloaded results to: " + tempFile.getAbsolutePath());
        return parseBatchResults(tempFile.getAbsolutePath());
    }
}
