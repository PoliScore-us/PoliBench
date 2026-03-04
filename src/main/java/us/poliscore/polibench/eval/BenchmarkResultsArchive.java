package us.poliscore.polibench.eval;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkResultsArchive {
    private String runDate;
    private List<String> models;
    private List<BenchmarkResult> results;
}
