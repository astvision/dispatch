package dispatch.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The JSON shapes Dispatch asks agents to answer in, compacted to one line (and so validated) when the class loads. */
public final class Schemas {

    public static final String PLAN = Json.read(resource("/plan-schema.json")).toString();
    public static final String SPLIT = Json.read(resource("/split-schema.json")).toString();
    public static final String ASSISTANT = Json.read(resource("/assistant-schema.json")).toString();

    private Schemas() {
    }

    /**
     * {@code schema} without length and count limits, which strict structured-output modes may refuse (OpenAI's, as Codex's
     * --output-schema uses). Dispatch enforces the plan's own limits when it reads the plan (Plan.parse).
     */
    public static String withoutLimits(String schema) {
        JsonNode node = Json.read(schema);
        strip(node);
        return node.toString();
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(java.util.List.of("minLength", "maxLength", "minItems", "maxItems"));
            object.forEach(Schemas::strip);
        } else if (node.isArray()) {
            node.forEach(Schemas::strip);
        }
    }

    private static String resource(String name) {
        try (InputStream in = Schemas.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
