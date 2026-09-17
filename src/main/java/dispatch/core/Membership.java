package dispatch.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.config.MemberWriter;
import dispatch.domain.OutboxKind;
import dispatch.domain.Requester;
import dispatch.store.JoinRequests;
import dispatch.store.Outbox;
import dispatch.store.Tx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Who may use a shared bot, decided in Telegram (ADR 0015). A private message from someone who is no member becomes a
 * request that every admin is asked about, with a button per group. Approving writes the member into the config and applies
 * it at once. A stranger causes at most one request and one message to each admin per day.
 */
public final class Membership {

    private static final Duration ASK_AGAIN_AFTER = Duration.ofDays(1);

    private final Groups groups;
    private final MemberWriter writer;
    private final Clock clock;
    private final Runnable wakeOutbox;

    public Membership(Groups groups, MemberWriter writer, Clock clock, Runnable wakeOutbox) {
        this.groups = groups;
        this.writer = writer;
        this.clock = clock;
        this.wakeOutbox = wakeOutbox;
    }

    /**
     * @param username  the person's Telegram @handle without "@", null when they have none
     * @param originRef their message, which the acknowledgement replies to
     */
    public JoinRequestResult requestJoin(Tx tx, Requester who, String username, String originRef) {
        Instant now = clock.instant();
        Optional<JoinRequests.JoinRequest> latest = JoinRequests.latest(tx, who.ref());
        if (latest.isPresent() && latest.get().status().equals("OPEN")) {
            return JoinRequestResult.PENDING;
        }
        if (latest.isPresent() && latest.get().status().equals("DENIED") && latest.get().decidedAt().plus(ASK_AGAIN_AFTER).isAfter(now)) {
            return JoinRequestResult.RECENTLY_DENIED;
        }
        long id = JoinRequests.insert(tx, who, username, now);
        Outbox.enqueue(tx, null, OutboxKind.JOIN_REQUESTED, who.ref(), originRef, Json.object(), now);
        ObjectNode payload = requestPayload(tx, id).orElseThrow();
        for (String admin : groups.admins()) {
            Outbox.enqueue(tx, null, OutboxKind.JOIN_REQUEST, admin, null, payload, now);
        }
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("membership.requested", "request", id, "requester", who.ref(), "admins", groups.admins().size()));
        return JoinRequestResult.REQUESTED;
    }

    /** Adds the person to {@code group}: first to the config file, then, once that worked, to the running groups. */
    public JoinDecision approve(Tx tx, Requester admin, long requestId, String group) {
        Optional<JoinDecision> refused = refusal(tx, admin, requestId);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (groups.all().stream().noneMatch(candidate -> candidate.name().equals(group))) {
            return JoinDecision.UNKNOWN_GROUP;
        }
        JoinRequests.JoinRequest request = JoinRequests.find(tx, requestId).orElseThrow();
        Config.Telegram updated;
        try {
            updated = writer.add(group, new Config.Member(Long.parseLong(request.requester().ref().substring("telegram:".length())),
                    request.requester().name()));
        } catch (RuntimeException e) {
            tx.afterCommit(() -> Log.error("membership.config_update_failed", e, "request", requestId, "group", group));
            return JoinDecision.CONFIG_FAILED;
        }
        Instant now = clock.instant();
        JoinRequests.approve(tx, requestId, group, admin, now);
        Outbox.enqueue(tx, null, OutboxKind.JOIN_APPROVED, request.requester().ref(), null, Json.object().put("group", group), now);
        tx.afterCommit(() -> groups.replace(updated));
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("membership.approved", "request", requestId, "requester", request.requester().ref(), "group", group,
                "admin", admin.ref()));
        return JoinDecision.APPROVED;
    }

    public JoinDecision deny(Tx tx, Requester admin, long requestId) {
        Optional<JoinDecision> refused = refusal(tx, admin, requestId);
        if (refused.isPresent()) {
            return refused.get();
        }
        Instant now = clock.instant();
        JoinRequests.JoinRequest request = JoinRequests.find(tx, requestId).orElseThrow();
        JoinRequests.deny(tx, requestId, admin, now);
        Outbox.enqueue(tx, null, OutboxKind.JOIN_DENIED, request.requester().ref(), null, Json.object(), now);
        tx.afterCommit(wakeOutbox);
        tx.afterCommit(() -> Log.info("membership.denied", "request", requestId, "requester", request.requester().ref(), "admin", admin.ref()));
        return JoinDecision.DENIED;
    }

    /** What an admin's message about the request shows: the person, and the groups to choose from or the decision. */
    public Optional<ObjectNode> requestPayload(Tx tx, long requestId) {
        return JoinRequests.find(tx, requestId).map(request -> {
            ObjectNode payload = Json.object().put("requestId", request.id()).put("name", request.requester().name())
                    .put("username", request.username())
                    .put("userId", Long.parseLong(request.requester().ref().substring("telegram:".length())))
                    .put("status", request.status()).put("group", request.groupName()).put("decidedBy", request.decidedByName());
            ArrayNode names = payload.putArray("groups");
            groups.all().forEach(group -> names.add(group.name()));
            return payload;
        });
    }

    private Optional<JoinDecision> refusal(Tx tx, Requester admin, long requestId) {
        if (!groups.isAdmin(admin.ref())) {
            tx.afterCommit(() -> Log.warn("membership.not_admin", "request", requestId, "presser", admin.ref()));
            return Optional.of(JoinDecision.NOT_ADMIN);
        }
        Optional<JoinRequests.JoinRequest> request = JoinRequests.find(tx, requestId);
        if (request.isEmpty()) {
            return Optional.of(JoinDecision.NOT_FOUND);
        }
        return request.get().status().equals("OPEN") ? Optional.empty() : Optional.of(JoinDecision.ALREADY_DECIDED);
    }
}
