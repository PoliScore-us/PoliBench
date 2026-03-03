package us.poliscore.polibench.providers;

import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;

import java.util.List;

public interface AiProvider {
    /**
     * @return The unique identifier of the provider model (e.g., "openai/gpt-5.2")
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
     * Submits the generated batch file to the provider's API.
     * 
     * @param batchFileOutputPath the path to the previously generated batch file
     * @return the provider-specific Job ID or Batch ID
     */
    String submitBatch(String batchFileOutputPath) throws Exception;

    /**
     * Polls the provider to see if the batch job is finished.
     * 
     * @param batchId the ID returned from submitBatch
     * @return true if complete (or failed/canceled), false if still processing
     */
    boolean isBatchComplete(String batchId) throws Exception;

    /**
     * Downloads the final results of the batch job, and converts it back
     * to a list of generic ModelResponses.
     * 
     * @param batchId the ID of the completed batch
     * @return List of generic ModelResponses
     */
    List<ModelResponse> fetchBatchResults(String batchId) throws Exception;

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
