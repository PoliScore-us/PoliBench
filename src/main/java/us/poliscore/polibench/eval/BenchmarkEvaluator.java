package us.poliscore.polibench.eval;

import java.util.EnumMap;
import java.util.Locale;
import java.util.regex.Pattern;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.Task;

/** Grades the seven structural pillars, without requiring unrelated full-report fields. */
public class BenchmarkEvaluator {
    private static final Pattern TAG = Pattern.compile("<\\s*(PASS|FAIL)\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");

    public record EvaluationOutcome(boolean passed, String failureReason, String error, boolean parseFailure) {
        public static EvaluationOutcome pass() { return new EvaluationOutcome(true, null, null, false); }
        public static EvaluationOutcome fail(String reason) { return new EvaluationOutcome(false, reason, null, false); }
        public static EvaluationOutcome failWithError(String reason, String error, boolean parseFailure) {
            return new EvaluationOutcome(false, reason, error, parseFailure);
        }
    }

    public boolean evaluate(Task task, ModelResponse response) { return evaluateWithOutcome(task, response).passed(); }

    public EvaluationOutcome evaluateWithOutcome(Task task, ModelResponse response) {
        if (task.getPillar() == null) throw new IllegalStateException("Task is missing its pillar assignment");
        String expected = task.getExpected().trim().toUpperCase(Locale.ROOT);
        if (!expected.equals("PASS") && !expected.equals("FAIL"))
            throw new IllegalStateException("Invalid expected outcome: " + expected);
        try {
            var outcomes = parse(response.getContent());
            boolean actual = outcomes.get(task.getPillar());
            return actual == expected.equals("PASS") ? EvaluationOutcome.pass()
                    : EvaluationOutcome.fail("Expected " + expected + " but model output resolved to " + (actual ? "PASS" : "FAIL"));
        } catch (IllegalArgumentException e) {
            return EvaluationOutcome.failWithError("Response could not be parsed: " + e.getMessage(), e.getMessage(), true);
        }
    }

    static EnumMap<Pillar, Boolean> parse(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Response was blank");
        // Strip emphasis to tolerate markdown around headings; isolate structural analysis from later sections.
        String text = ANSI.matcher(content).replaceAll("").replace("**", "").replace("__", "");
        var section = Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?Structural Analysis\\s*:").matcher(text);
        if (!section.find()) throw new IllegalArgumentException("Missing Structural Analysis section");
        text = text.substring(section.end());
        var end = Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?(?:Impact Analysis|Long Report|Confidence|Impact Stats|Rating)\\s*:").matcher(text);
        if (end.find()) text = text.substring(0, end.start());
        var bodies = new EnumMap<Pillar, StringBuilder>(Pillar.class);
        Pillar current = null;
        for (String line : text.split("\\R")) {
            boolean heading = false;
            for (Pillar pillar : Pillar.values()) {
                var match = Pattern.compile("^\\s*(?:#{1,6}\\s*)?" + (pillar.ordinal() + 1)
                        + "[.)]?\\s*[*_]?" + pillar.getValue() + "[*_]?\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(line);
                if (match.matches()) {
                    if (bodies.containsKey(pillar)) throw new IllegalArgumentException("Duplicate pillar: " + pillar.getValue());
                    current = pillar;
                    bodies.put(pillar, new StringBuilder(match.group(1)));
                    heading = true;
                    break;
                }
            }
            if (!heading && current != null) bodies.get(current).append('\n').append(line);
        }
        var outcomes = new EnumMap<Pillar, Boolean>(Pillar.class);
        for (Pillar pillar : Pillar.values()) {
            var body = bodies.get(pillar);
            if (body == null) throw new IllegalArgumentException("Missing pillar: " + pillar.getValue());
            var tags = TAG.matcher(body);
            Boolean last = null;
            while (tags.find()) last = tags.group(1).equalsIgnoreCase("PASS");
            if (last == null) throw new IllegalArgumentException("Missing verdict: " + pillar.getValue());
            if (TAG.matcher(body).replaceAll("").isBlank()) throw new IllegalArgumentException("Missing analysis: " + pillar.getValue());
            outcomes.put(pillar, last);
        }
        return outcomes;
    }
}
