package us.poliscore.polibench.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ModelRequest {
    private String requestId;
    private String systemPrompt;
    private String userPrompt;
    private Task task;
}
