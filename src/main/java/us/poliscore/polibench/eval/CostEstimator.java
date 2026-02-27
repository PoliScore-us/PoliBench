package us.poliscore.polibench.eval;

public class CostEstimator {

    // Very rough heuristic for English text
    private static final double WORDS_TO_TOKENS_RATIO = 1.3;

    public static int estimateTokens(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        return (int) Math.ceil(words.length * WORDS_TO_TOKENS_RATIO);
    }
}
