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
     * Executes the request set and returns provider-normalized responses.
     */
    List<ModelResponse> executeRequests(List<ModelRequest> requests) throws Exception;

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
