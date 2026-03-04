package us.poliscore.polibench.eval;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkResultsArchive {
    private String runDate;
    private List<String> models;
    private String systemPrompt;
    private List<BenchmarkResult> results;
}
