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
        if (response.isIncomplete())
            return EvaluationOutcome.failWithError("Generation was incomplete", "Incomplete generation", true);
        try {
            var outcomes = parse(response.getContent());
            boolean actual = outcomes.get(task.getPillar());
            return actual == expected.equals("PASS") ? EvaluationOutcome.pass()
                    : EvaluationOutcome.fail("Expected " + expected + " but model output resolved to " + (actual ? "PASS" : "FAIL"));
        } catch (IllegalArgumentException e) {
            return EvaluationOutcome.failWithError("Response could not be parsed: " + e.getMessage(), e.getMessage(), true);
        }
    }

    public Boolean targetVerdict(Task task, ModelResponse response) {
        if (response == null) return null;
        try { return parse(response.getContent(), task.getPillar()).get(task.getPillar()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public boolean structurallyValid(ModelResponse response) {
        if (response == null) return false;
        try { parse(response.getContent()); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    public boolean reportComplete(ModelResponse response) {
        if (response == null || response.isIncomplete() || !structurallyValid(response)) return false;
        String text = normalize(response.getContent());
        String[] headings = {"Neutral Summary", "Bill Title", "Other Names", "Topics", "Structural Analysis",
                "Impact Analysis", "Long Report", "Confidence", "Impact Stats", "Rating", "Casual Report", "Short Report"};
        int previousEnd = -1;
        for (String heading : headings) {
            var match = Pattern.compile("(?im)^\\h*(?:#{1,6}\\h*)?" + heading + "(?:\\h*:|\\h*$)").matcher(text);
            if (!match.find()) return false;
            int start = match.start(), end = match.end();
            if (start < previousEnd || (previousEnd >= 0 && text.substring(previousEnd, start).isBlank())) return false;
            previousEnd = end;
            if (match.find()) return false;
        }
        return !text.substring(previousEnd).replace("```", "").isBlank();
    }

    private static String normalize(String content) {
        return ANSI.matcher(content).replaceAll("").replace("**", "").replace("__", "");
    }

    static EnumMap<Pillar, Boolean> parse(String content) { return parse(content, null); }

    private static EnumMap<Pillar, Boolean> parse(String content, Pillar target) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Response was blank");
        // Strip emphasis to tolerate markdown around headings; isolate structural analysis from later sections.
        String text = normalize(content);
        // A colon may introduce content on the same line; a colonless heading must stand alone.
        var section = Pattern.compile("(?im)^\\h*(?:#{1,6}\\h*)?Structural Analysis(?:\\h*:\\h*|\\h*$)").matcher(text);
        if (!section.find()) throw new IllegalArgumentException("Missing Structural Analysis section");
        int sectionEnd = section.end();
        if (section.find()) throw new IllegalArgumentException("Duplicate Structural Analysis section");
        text = text.substring(sectionEnd);
        var end = Pattern.compile("(?im)^\\h*(?:#{1,6}\\h*)?(?:Impact Analysis|Long Report|Confidence|Impact Stats|Rating)(?:\\h*:\\h*|\\h*$)").matcher(text);
        if (end.find()) text = text.substring(0, end.start());
        var bodies = new EnumMap<Pillar, StringBuilder>(Pillar.class);
        Pillar current = null;
        for (String line : text.split("\\R")) {
            boolean heading = false;
            for (Pillar pillar : Pillar.values()) {
                var match = Pattern.compile("^\\h*(?:#{1,6}\\h*)?(?:[-*]\\h+)?" + (pillar.ordinal() + 1)
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
            if (target != null && pillar != target) continue;
            var body = bodies.get(pillar);
            if (body == null) throw new IllegalArgumentException("Missing pillar: " + pillar.getValue());
            var tags = TAG.matcher(body);
            Boolean last = null;
            while (tags.find()) {
                boolean verdict = tags.group(1).equalsIgnoreCase("PASS");
                if (last != null && last != verdict)
                    throw new IllegalArgumentException("Conflicting verdicts: " + pillar.getValue());
                last = verdict;
            }
            if (last == null) throw new IllegalArgumentException("Missing verdict: " + pillar.getValue());
            if (TAG.matcher(body).replaceAll("").isBlank()) throw new IllegalArgumentException("Missing analysis: " + pillar.getValue());
            outcomes.put(pillar, last);
        }
        return outcomes;
    }
}
