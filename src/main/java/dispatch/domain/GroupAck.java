package dispatch.domain;

/** A member's own choice of how a group hears about their task, replacing the old ✉️ line by default (G-1e). */
public enum GroupAck {
    REACTION, REACTION_AND_LINE, SILENT;

    /** @throws IllegalArgumentException for anything but the three values the Mini App and the config offer */
    public static GroupAck fromValue(String value) {
        return switch (value) {
            case "reaction" -> REACTION;
            case "reactionAndLine" -> REACTION_AND_LINE;
            case "silent" -> SILENT;
            default -> throw new IllegalArgumentException("unknown groupAck: " + value);
        };
    }

    public String value() {
        return switch (this) {
            case REACTION -> "reaction";
            case REACTION_AND_LINE -> "reactionAndLine";
            case SILENT -> "silent";
        };
    }
}
