package us.poliscore.polibench.providers;

import java.util.ArrayList;
import java.util.List;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;

public class MockProvider implements AiProvider {
    public String getModelId() { return "mock"; }
    public double calculateEstimatedCost(int promptTokens, int completionTokens) { return 0; }
    public List<ModelResponse> parseBatchResults(String path) {
        throw new UnsupportedOperationException("Mock provider does not parse OpenRouter output files");
    }
    public List<ModelResponse> executeRequests(List<ModelRequest> requests) {
        var responses = new ArrayList<ModelResponse>();
        for (var request : requests) {
            var content = new StringBuilder("Structural Analysis:\n");
            for (Pillar pillar : Pillar.values()) {
                String verdict = pillar == request.getTask().getPillar() ? request.getTask().getExpected() : "PASS";
                content.append(pillar.ordinal() + 1).append(". ").append(pillar.getValue())
                        .append(": Mock analysis. <").append(verdict).append(">\n");
            }
            content.append("Impact Analysis:\nMock impact analysis.");
            responses.add(new ModelResponse(request.getRequestId(), content.toString(), 10, 10));
        }
        return responses;
    }
}
