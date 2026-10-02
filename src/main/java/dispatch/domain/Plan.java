package dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The agent's read-only analysis of a task, in the shape required by plan-schema.json. A question is an object with
 * answer options (G-1d); a plain string, as plans stored before then have it, is a question without options. Decisions
 * came later still, and plugin picks after them, so a stored plan may have neither.
 */
public record Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                   List<PlanQuestion> questionItems, List<PlanDecision> decisions, List<String> plugins, Result result,
                   String answer) {

    /** What the plan run returned (spec: answers): a plan to approve, or the answer to a task that only asks something. */
    public enum Result {
        PLAN, ANSWER;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final int MAX_OPTIONS = 4;
    public static final int MAX_OPTION_LENGTH = 40;
    public static final int MAX_ALTERNATIVES = 3;
    private static final Set<String> FIELDS = Set.of("understanding", "findings", "steps", "risks", "questions", "decisions",
            "plugins", "result", "answer");

    public Plan {
        findings = List.copyOf(findings);
        steps = List.copyOf(steps);
        risks = List.copyOf(risks);
        questionItems = List.copyOf(questionItems);
        decisions = List.copyOf(decisions);
        plugins = List.copyOf(plugins);
        answer = answer == null ? "" : answer;
    }

    /** A plan, not an answer. */
    public Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                List<PlanQuestion> questionItems, List<PlanDecision> decisions, List<String> plugins) {
        this(understanding, findings, steps, risks, questionItems, decisions, plugins, Result.PLAN, "");
    }

    /** Whether {@code planJson}, a task's stored plan, is an answer: such a task never executed (spec: answers). */
    public static boolean answers(String planJson) {
        return planJson != null && parse(planJson).result() == Result.ANSWER;
    }

    /** A plan without plugin picks. */
    public Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                List<PlanQuestion> questionItems, List<PlanDecision> decisions) {
        this(understanding, findings, steps, risks, questionItems, decisions, List.of());
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
        Result result = result(node);
        String answer = "";
        if (result == Result.ANSWER) {
            JsonNode answerNode = node.get("answer");
            if (answerNode == null || !answerNode.isTextual() || answerNode.asText().isBlank()) {
                throw new InvalidPlanException("plan field 'answer' must be non-blank text when result is \"answer\"");
            }
            answer = answerNode.asText();
        } else if (steps.isEmpty() && questions.isEmpty()) {
            throw new InvalidPlanException("plan has neither steps nor questions");
        }
        return new Plan(understandingNode.asText(), texts(node, "findings"), steps, texts(node, "risks"), questions,
                decisions(node), plugins(node), result, answer);
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
        plugins.forEach(json.putArray("plugins")::add);
        if (result == Result.ANSWER) {
            // Only an answer says so: a plan stays readable by a jar from before answers, which a rollback runs.
            json.put("result", result.json()).put("answer", answer);
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

    /**
     * Absent in a plan stored before plugin picks. A name outside {@link CuratedPlugins} is dropped rather than the plan
     * rejected: the agent only suggested it, and the plan works without it.
     */
    private static List<String> plugins(JsonNode plan) {
        JsonNode array = plan.get("plugins");
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new InvalidPlanException("plan field 'plugins' must be an array");
        }
        List<String> plugins = new ArrayList<>();
        for (JsonNode item : array) {
            Optional<String> name = CuratedPlugins.match(item.asText(""));
            if (name.isEmpty()) {
                Log.info("plan.plugin_dropped", "plugin", item.toString());
            } else if (!plugins.contains(name.get())) {
                plugins.add(name.get());
            }
        }
        return plugins;
    }

    /** Absent in a plan stored before answers, which is a plan. */
    private static Result result(JsonNode plan) {
        JsonNode result = plan.get("result");
        if (result == null) {
            return Result.PLAN;
        }
        return switch (result.asText("")) {
            case "plan" -> Result.PLAN;
            case "answer" -> Result.ANSWER;
            default -> throw new InvalidPlanException("plan field 'result' must be \"plan\" or \"answer\"");
        };
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
