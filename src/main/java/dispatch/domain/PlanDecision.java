package dispatch.domain;

import java.util.List;

/**
 * A choice the agent made on its own, which the requester may change with one tap without holding the plan up: unlike a
 * {@link PlanQuestion}, it never blocks approval, and approving takes {@code chosen}.
 *
 * @param chosen       what the plan assumes, at most {@link Plan#MAX_OPTION_LENGTH} characters
 * @param alternatives 1 to {@link Plan#MAX_ALTERNATIVES} other answers, each as short; tapping one plans again with it
 */
public record PlanDecision(String text, String chosen, List<String> alternatives) {

    public PlanDecision {
        alternatives = List.copyOf(alternatives);
    }
}
