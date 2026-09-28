package dispatch.ui;

import com.sun.net.httpserver.HttpExchange;
import dispatch.Text;
import dispatch.core.Groups;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * The desk port's authentication (D-2): the token from desk.json on every request, and the member the owner acts as — a
 * personal bot's one member, a team's only admin, or the admin the page names when there are several.
 */
final class DeskAuth implements UiServer.Auth {

    static final String MEMBER_HEADER = "X-Dispatch-Member";

    private final int port;
    private final byte[] token;
    private final Groups groups;

    DeskAuth(int port, String token, Groups groups) {
        this.port = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.groups = groups;
    }

    @Override
    public Optional<String> hostRefusal(String host) {
        return ("127.0.0.1:" + port).equals(host) ? Optional.empty() : Optional.of("this port answers 127.0.0.1:" + port + " only");
    }

    @Override
    public void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    @Override
    public Optional<String> pageRefusal(HttpExchange exchange) {
        return Optional.of("the desk port serves no pages");
    }

    @Override
    public UiServer.Caller caller(HttpExchange exchange) {
        String given = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] offered = given != null && given.startsWith("desk ") ? given.substring(5).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(offered, token)) {
            throw new ApiException(401, "desk_token", Text.of("refusal.deskToken"));
        }
        String named = exchange.getRequestHeaders().getFirst(MEMBER_HEADER);
        String ref = named != null && !named.isBlank() ? named.strip() : onlyCandidate();
        if (!groups.mayManage(ref)) {
            throw new ApiException(403, "not_owner", Text.of("refusal.notOwner", ref));
        }
        return new UiServer.Caller(ref, groups.memberName(ref).orElse(ref), true);
    }

    /**
     * The one member the owner can be when the page names none: a personal bot's member, or a team's only admin. A team
     * with several admins is asked which one; a team with none has nobody the desk may act as.
     */
    private String onlyCandidate() {
        List<String> candidates = groups.isPersonal()
                ? groups.all().stream().flatMap(group -> group.members().stream()).map(member -> "telegram:" + member.id())
                        .distinct().toList()
                : groups.admins();
        if (candidates.isEmpty()) {
            throw new ApiException(403, "no_owner", Text.of("refusal.noOwner"));
        }
        if (candidates.size() > 1) {
            throw new ApiException(409, "choose_member", Text.of("refusal.chooseMember"));
        }
        return candidates.getFirst();
    }
}
