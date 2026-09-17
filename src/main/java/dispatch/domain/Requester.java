package dispatch.domain;

/**
 * Someone acting through a channel.
 *
 * @param ref  channel-qualified identity, e.g. "telegram:123456789"
 * @param name display name at the time of the action
 */
public record Requester(String ref, String name) {
}
