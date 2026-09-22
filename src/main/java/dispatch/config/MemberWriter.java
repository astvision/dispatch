package dispatch.config;

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

    /**
     * Edits the config file in place and validates it before replacing it, as dispatch project add does.
     * {@link ConfigFile#edit} holds a lock around the read, the presence check and the write, so a member the running
     * service is adding here can never be lost to a concurrent {@code dispatch project add} or a management-page save.
     */
    static MemberWriter file(Path configFile, Map<String, String> environment) {
        return (group, member) -> ConfigFile.edit(configFile, environment, current -> {
            Config config = ConfigFile.parse(configFile, current, environment);
            boolean present = config.telegram().groups().stream()
                    .anyMatch(candidate -> candidate.name().equals(group)
                            && candidate.members().stream().anyMatch(existing -> existing.id() == member.id()));
            return present ? current : ConfigText.addMember(current, group, member.id(), member.name());
        }).telegram();
    }
}
