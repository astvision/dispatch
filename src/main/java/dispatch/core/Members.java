package dispatch.core;

import dispatch.config.Config;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The team's allowlist. Members are configured by Telegram user id, the only channel so far. */
public final class Members {

    private final Set<String> refs;

    public Members(List<Config.Member> members) {
        this.refs = members.stream().map(member -> "telegram:" + member.id()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean contains(String requesterRef) {
        return refs.contains(requesterRef);
    }
}
