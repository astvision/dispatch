package dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The agent's read-only analysis of a task, in the shape required by plan-schema.json. A question is an object with
 * answer options (G-1d); a plain string, as plans stored before then have it, is a question without options. Decisions
 * came later still, so a stored plan may have none.
 */
public record Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                   List<PlanQuestion> questionItems, List<PlanDecision> decisions) {

    public static final int MAX_OPTIONS = 4;
    public static final int MAX_OPTION_LENGTH = 40;
    public static final int MAX_ALTERNATIVES = 3;
    private static final Set<String> FIELDS = Set.of("understanding", "findings", "steps", "risks", "questions", "decisions");

    public Plan {
        findings = List.copyOf(findings);
        steps = List.copyOf(steps);
        risks = List.copyOf(risks);
        questionItems = List.copyOf(questionItems);
        decisions = List.copyOf(decisions);
    }

    public Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                List<PlanQuestion> questionItems) {
        this(understanding, findings, steps, risks, questionItems, List.of());
    }

    /** The open questions' texts. */
    public List<String> questions() {
        return questionItems.stream().map(PlanQuestion::text).toList();
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
        List<PlanQuestion> questions = questions(node);
        if (steps.isEmpty() && questions.isEmpty()) {
            throw new InvalidPlanException("plan has neither steps nor questions");
        }
        return new Plan(understandingNode.asText(), texts(node, "findings"), steps, texts(node, "risks"), questions,
                decisions(node));
    }

    public String toJson() {
        ObjectNode json = Json.object().put("understanding", understanding);
        findings.forEach(json.putArray("findings")::add);
        steps.forEach(json.putArray("steps")::add);
        risks.forEach(json.putArray("risks")::add);
        ArrayNode questionsJson = json.putArray("questions");
        for (PlanQuestion question : questionItems) {
            ArrayNode options = questionsJson.addObject().put("text", question.text()).putArray("options");
            question.options().forEach(options::add);
        }
        ArrayNode decisionsJson = json.putArray("decisions");
        for (PlanDecision decision : decisions) {
            ArrayNode alternatives = decisionsJson.addObject().put("text", decision.text()).put("chosen", decision.chosen())
                    .putArray("alternatives");
            decision.alternatives().forEach(alternatives::add);
        }
        return json.toString();
    }

    /**
     * Options past the fourth are dropped and longer ones cut, rather than the whole plan rejected: the plan is still
     * usable, and a button label has little room anyway.
     */
    private static List<PlanQuestion> questions(JsonNode plan) {
        JsonNode array = plan.get("questions");
        if (array == null || !array.isArray()) {
            throw new InvalidPlanException("plan field 'questions' must be an array");
        }
        List<PlanQuestion> questions = new ArrayList<>();
        for (JsonNode item : array) {
            JsonNode text = item.isObject() ? item.get("text") : item;
            if (text == null || !text.isTextual() || text.asText().isBlank()) {
                throw new InvalidPlanException("plan field 'questions' contains a question without text");
            }
            List<String> options = new ArrayList<>();
            for (JsonNode option : item.path("options")) {
                if (!option.isTextual()) {
                    throw new InvalidPlanException("plan field 'questions' contains a non-text option");
                }
                String label = option.asText().strip();
                if (!label.isEmpty() && options.size() < MAX_OPTIONS) {
                    options.add(buttonLabel(label));
                }
            }
            questions.add(new PlanQuestion(text.asText(), options));
        }
        return questions;
    }

    /**
     * Absent in a plan stored before decisions existed. Alternatives past the third are dropped and labels cut, as options
     * are; a decision with nothing to switch to is no decision, and is rejected.
     */
    private static List<PlanDecision> decisions(JsonNode plan) {
        JsonNode array = plan.get("decisions");
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new InvalidPlanException("plan field 'decisions' must be an array");
        }
        List<PlanDecision> decisions = new ArrayList<>();
        for (JsonNode item : array) {
            String text = item.path("text").asText("").strip();
            String chosen = item.path("chosen").asText("").strip();
            if (text.isEmpty() || chosen.isEmpty()) {
                throw new InvalidPlanException("plan field 'decisions' contains a decision without text or chosen answer");
            }
            List<String> alternatives = new ArrayList<>();
            for (JsonNode alternative : item.path("alternatives")) {
                String label = alternative.asText("").strip();
                if (!label.isEmpty() && alternatives.size() < MAX_ALTERNATIVES) {
                    alternatives.add(buttonLabel(label));
                }
            }
            if (alternatives.isEmpty()) {
                throw new InvalidPlanException("plan field 'decisions' contains a decision without alternatives");
            }
            decisions.add(new PlanDecision(text, buttonLabel(chosen), alternatives));
        }
        return decisions;
    }

    private static String buttonLabel(String label) {
        return label.codePointCount(0, label.length()) <= MAX_OPTION_LENGTH
                ? label
                : label.substring(0, label.offsetByCodePoints(0, MAX_OPTION_LENGTH - 1)) + "…";
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
