package dispatch.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Adds a member to a group in the config the instance runs with (ADR 0015). */
@FunctionalInterface
public interface MemberWriter {

    /**
     * @return the groups and admins as the config holds them afterwards
     * @throws ConfigException when the config cannot take the member, e.g. an unknown group; the config is unchanged
     */
    Config.Telegram add(String group, Config.Member member);

    /** Edits the config file in place and validates it before replacing it, as dispatch project add does. */
    static MemberWriter file(Path configFile, Map<String, String> environment) {
        Object lock = new Object();
        return (group, member) -> {
            synchronized (lock) {
                Config current = ConfigLoader.load(configFile, environment);
                boolean present = current.telegram().groups().stream()
                        .anyMatch(candidate -> candidate.name().equals(group)
                                && candidate.members().stream().anyMatch(existing -> existing.id() == member.id()));
                if (present) {
                    return current.telegram();
                }
                try {
                    String edited = ConfigText.addMember(Files.readString(configFile), group, member.id(), member.name());
                    return ConfigFile.replace(configFile, edited, environment).telegram();
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read " + configFile + ": " + e.getMessage(), e);
                }
            }
        };
    }
}
