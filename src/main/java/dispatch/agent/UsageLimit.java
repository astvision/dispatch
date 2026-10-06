package dispatch.agent;

/**
 * The usage limit that cut a run short, as Claude Code's rate_limit_event reported it (spec: usage limit).
 *
 * @param resetsAt when the limiting window resets, in epoch seconds (plain JSON: a worker sends it to its team machine)
 * @param type     Claude's name for the window: five_hour, seven_day, seven_day_opus, ...
 */
public record UsageLimit(long resetsAt, String type) {
}
