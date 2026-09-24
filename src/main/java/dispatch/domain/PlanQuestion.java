package dispatch.domain;

import java.util.List;

/**
 * A plan's open question and the short answers the agent expects, offered as buttons (G-1d).
 *
 * @param options at most {@link Plan#MAX_OPTIONS}, each at most {@link Plan#MAX_OPTION_LENGTH} characters; empty for a
 *                question from before options existed
 */
public record PlanQuestion(String text, List<String> options) {

    public PlanQuestion {
        options = List.copyOf(options);
    }
}
