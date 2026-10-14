package us.poliscore.polibench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The prompt and generation limits are versioned benchmark inputs, independent of PoliScore runtime. */
public final class BenchmarkDefinition {
    public static final int MAX_COMPLETION_TOKENS = 8192;
    private BenchmarkDefinition() {}

    public static String systemPrompt() {
        try (var stream = BenchmarkDefinition.class.getResourceAsStream("/system-prompt.txt")) {
            if (stream == null) throw new IllegalStateException("Missing system-prompt.txt");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load benchmark prompt", e);
        }
    }
}
