package dispatch.domain;

/**
 * What a group-origin task's reaction on its originating message shows, instead of the old ✉️ line (G-1e). Each new
 * state replaces whatever reaction Telegram showed for the previous one.
 */
public enum GroupReaction {
    PROMPT_DELIVERED("👀"),
    TASK_CREATED("✍"),
    COMPLETED("👍"),
    ENDED("👎");

    private final String emoji;

    GroupReaction(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }
}
