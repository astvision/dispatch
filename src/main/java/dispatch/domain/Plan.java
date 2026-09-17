package dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** The agent's read-only analysis of a task, in the shape required by plan-schema.json. */
public record Plan(String understanding, List<String> findings, List<String> steps, List<String> risks, List<String> questions) {

    private static final Set<String> FIELDS = Set.of("understanding", "findings", "steps", "risks", "questions");

    public Plan {
        findings = List.copyOf(findings);
        steps = List.copyOf(steps);
        risks = List.copyOf(risks);
        questions = List.copyOf(questions);
    }

    /** Validates agent output; a plan the team cannot act on is rejected here rather than posted. */
    public static Plan parse(String json) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidPlanException("plan is not valid JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new InvalidPlanException("plan must be a JSON object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                throw new InvalidPlanException("plan has unexpected field '" + name + "'");
            }
        }
        JsonNode understandingNode = node.get("understanding");
        if (understandingNode == null || !understandingNode.isTextual() || understandingNode.asText().isBlank()) {
            throw new InvalidPlanException("plan field 'understanding' must be non-blank text");
        }
        List<String> steps = texts(node, "steps");
        List<String> questions = texts(node, "questions");
        if (steps.isEmpty() && questions.isEmpty()) {
            throw new InvalidPlanException("plan has neither steps nor questions");
        }
        return new Plan(understandingNode.asText(), texts(node, "findings"), steps, texts(node, "risks"), questions);
    }

    public String toJson() {
        return Json.write(this);
    }

    private static List<String> texts(JsonNode plan, String field) {
        JsonNode array = plan.get(field);
        if (array == null || !array.isArray()) {
            throw new InvalidPlanException("plan field '" + field + "' must be an array of text");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new InvalidPlanException("plan field '" + field + "' contains a blank or non-text item");
            }
            items.add(item.asText());
        }
        return items;
    }
}
