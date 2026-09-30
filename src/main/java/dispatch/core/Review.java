package dispatch.core;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The verify loop reviewer's answer (review-schema.json), checked here because not every agent enforces a schema. */
public record Review(String verdict, List<Finding> findings) {

    private static final Set<String> VERDICTS = Set.of("ok", "changes");
    private static final Set<String> SEVERITIES = Set.of("blocking", "minor");
    /** review-schema.json's maxItems and maxLength, which Codex and Gemini do not enforce. */
    static final int MAX_FINDINGS = 20;
    static final int MAX_TEXT = 500;

    public Review {
        findings = List.copyOf(findings);
    }

    /** @param line 0 when the finding has no line */
    public record Finding(String severity, String file, int line, String text) {
    }

    public List<Finding> blocking() {
        return findings.stream().filter(finding -> finding.severity().equals("blocking")).toList();
    }

    public List<Finding> minor() {
        return findings.stream().filter(finding -> finding.severity().equals("minor")).toList();
    }

    /** @throws IllegalArgumentException with the reason when {@code answer} is not a review */
    public static Review parse(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("the reviewer gave no answer");
        }
        JsonNode node;
        try {
            node = Json.read(unfenced(answer.strip()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the reviewer's answer is not JSON: " + e.getMessage(), e);
        }
        String verdict = node.path("verdict").asText("");
        if (!VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("unknown verdict: " + verdict);
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : node.path("findings")) {
            if (findings.size() == MAX_FINDINGS) {
                break;
            }
            String severity = item.path("severity").asText("");
            if (!SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException("unknown severity: " + severity);
            }
            String text = item.path("text").asText("");
            findings.add(new Finding(severity, item.path("file").asText(""), item.path("line").asInt(0),
                    text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text));
        }
        return new Review(verdict, findings);
    }

    private static String unfenced(String answer) {
        if (!answer.startsWith("```")) {
            return answer;
        }
        int start = answer.indexOf('\n');
        int end = answer.lastIndexOf("```");
        return start < 0 || end <= start ? answer : answer.substring(start + 1, end).strip();
    }
}
