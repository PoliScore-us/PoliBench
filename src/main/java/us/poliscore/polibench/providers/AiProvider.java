package us.poliscore.polibench.providers;

import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import java.util.List;

public interface AiProvider {
    /**
     * @return The unique identifier of the provider model (e.g., "gpt-4o")
     */
    String getModelId();

    /**
     * Converts generic ModelRequests into the provider's specific batch format
     * 
     * @param requests            the generic requests
     * @param batchFileOutputPath the path where the batch file should be saved
     */
    void generateBatchFile(List<ModelRequest> requests, String batchFileOutputPath) throws Exception;

    /**
     * Converts the provider's resulting batch output file back into generic
     * ModelResponses.
     * 
     * @param batchResultInputPath the path to the completed batch results file
     * @return List of ModelResponse
     */
    List<ModelResponse> parseBatchResults(String batchResultInputPath) throws Exception;

    /**
     * Calculates the estimated cost of standard execution given the input/output
     * tokens.
     */
    double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens);
}
