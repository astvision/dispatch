package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.config.Config;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.SqlRows;
import dispatch.testing.TestClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** People join a shared bot when an admin approves them in Telegram (ADR 0015). */
class MembershipTest {

    private static final Requester ADMIN = new Requester("telegram:900", "Nomin");
    private static final Requester BOLD = new Requester("telegram:100", "Bold");
    private static final Requester ALI = new Requester("telegram:200", "Ali");

    @TempDir
    Path dir;

    private Path dbFile;
    private Database db;
    private TestClock clock;
    private Groups groups;
    private final List<String> written = new ArrayList<>();
    private boolean configBroken;
    private Membership membership;

    @BeforeEach
    void setUp() {
        dbFile = dir.resolve("dispatch.db");
        db = Database.open(dbFile);
        db.migrate();
        clock = new TestClock(Instant.parse("2026-09-18T10:00:00Z"));
        groups = new Groups(telegram(List.of(new Config.Member(100, "Bold")), List.of(new Config.Member(300, "Sara"))));
        membership = new Membership(groups, this::write, clock, () -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void strangersRequestReachesEveryAdminWithAButtonPerGroup() {
        assertEquals(JoinRequestResult.REQUESTED, request());

        Map<String, String> ack = row("SELECT * FROM outbox WHERE kind = 'JOIN_REQUESTED'");
        assertEquals("telegram:200", ack.get("chat_ref"));
        assertEquals("telegram:200/5", ack.get("reply_to_ref"));
        Map<String, String> toAdmin = row("SELECT * FROM outbox WHERE kind = 'JOIN_REQUEST'");
        assertEquals("telegram:900", toAdmin.get("chat_ref"));
        JsonNode payload = Json.read(toAdmin.get("payload"));
        assertEquals("Ali", payload.get("name").asText());
        assertEquals("ali", payload.get("username").asText());
        assertEquals(200, payload.get("userId").asLong());
        assertEquals("OPEN", payload.get("status").asText());
        assertEquals(List.of("backend", "mobile"), List.of(payload.get("groups").get(0).asText(), payload.get("groups").get(1).asText()));
        assertEquals("OPEN", row("SELECT status FROM join_request").get("status"));
    }

    @Test
    void waitingOrRecentlyDeniedPeopleCauseNoFurtherMessages() {
        request();
        assertEquals(JoinRequestResult.PENDING, request());
        long id = requestId();
        assertEquals(JoinDecision.DENIED, db.transactionReturning(tx -> membership.deny(tx, ADMIN, id)));
        clock.advance(Duration.ofHours(23));
        assertEquals(JoinRequestResult.RECENTLY_DENIED, request());
        assertEquals("3", row("SELECT count(*) AS n FROM outbox").get("n"), "the request, its acknowledgement and the denial");

        clock.advance(Duration.ofHours(2));

        assertEquals(JoinRequestResult.REQUESTED, request());
        assertEquals("2", row("SELECT count(*) AS n FROM outbox WHERE kind = 'JOIN_REQUEST'").get("n"));
        assertEquals("telegram:200", row("SELECT chat_ref FROM outbox WHERE kind = 'JOIN_DENIED'").get("chat_ref"));
    }

    @Test
    void approvalAddsThemToTheChosenGroupWithoutARestart() {
        request();
        long id = requestId();

        assertEquals(JoinDecision.APPROVED, db.transactionReturning(tx -> membership.approve(tx, ADMIN, id, "mobile")));

        assertEquals(List.of("mobile:200:Ali"), written);
        assertTrue(groups.isMember(ALI.ref()), "effective right after the decision");
        assertEquals(Set.of("life"), groups.projectsOfMember(ALI.ref()));
        Map<String, String> request = row("SELECT * FROM join_request WHERE id = ?", id);
        assertEquals("APPROVED", request.get("status"));
        assertEquals("mobile", request.get("group_name"));
        assertEquals("telegram:900", request.get("decided_by"));
        Map<String, String> approved = row("SELECT * FROM outbox WHERE kind = 'JOIN_APPROVED'");
        assertEquals("telegram:200", approved.get("chat_ref"));
        assertEquals("mobile", Json.read(approved.get("payload")).get("group").asText());
        JsonNode redraw = db.transactionReturning(tx -> membership.requestPayload(tx, id)).orElseThrow();
        assertEquals("APPROVED", redraw.get("status").asText());
        assertEquals("Nomin", redraw.get("decidedBy").asText());
    }

    @Test
    void onlyAdminsDecideAndOnlyOnce() {
        request();
        long id = requestId();

        assertEquals(JoinDecision.NOT_ADMIN, db.transactionReturning(tx -> membership.approve(tx, BOLD, id, "backend")));
        assertEquals(JoinDecision.UNKNOWN_GROUP, db.transactionReturning(tx -> membership.approve(tx, ADMIN, id, "sales")));
        assertEquals(JoinDecision.NOT_FOUND, db.transactionReturning(tx -> membership.approve(tx, ADMIN, 999, "backend")));
        assertEquals(JoinDecision.APPROVED, db.transactionReturning(tx -> membership.approve(tx, ADMIN, id, "backend")));
        assertEquals(JoinDecision.ALREADY_DECIDED, db.transactionReturning(tx -> membership.deny(tx, ADMIN, id)));
        assertEquals(1, written.size());
    }

    @Test
    void configThatCannotBeUpdatedLeavesTheRequestOpen() {
        request();
        long id = requestId();
        configBroken = true;

        assertEquals(JoinDecision.CONFIG_FAILED, db.transactionReturning(tx -> membership.approve(tx, ADMIN, id, "backend")));

        assertEquals("OPEN", row("SELECT status FROM join_request WHERE id = ?", id).get("status"));
        assertFalse(groups.isMember(ALI.ref()));
        assertEquals("0", row("SELECT count(*) AS n FROM outbox WHERE kind = 'JOIN_APPROVED'").get("n"));
    }

    private JoinRequestResult request() {
        return db.transactionReturning(tx -> membership.requestJoin(tx, ALI, "ali", "telegram:200/5"));
    }

    private long requestId() {
        return Long.parseLong(row("SELECT max(id) AS id FROM join_request").get("id"));
    }

    /** The config file as a test double: remembers what was added and returns the groups with it. */
    private Config.Telegram write(String group, Config.Member member) {
        if (configBroken) {
            throw new IllegalStateException("config file is not writable");
        }
        written.add(group + ":" + member.id() + ":" + member.name());
        List<Config.Member> backend = new ArrayList<>(List.of(new Config.Member(100, "Bold")));
        List<Config.Member> mobile = new ArrayList<>(List.of(new Config.Member(300, "Sara")));
        (group.equals("backend") ? backend : mobile).add(member);
        return telegram(backend, mobile);
    }

    private static Config.Telegram telegram(List<Config.Member> backend, List<Config.Member> mobile) {
        return new Config.Telegram(List.of(900L), List.of(new Config.Group("backend", -100L, backend, List.of("alm")),
                new Config.Group("mobile", null, mobile, List.of("life"))));
    }

    private Map<String, String> row(String sql, Object... params) {
        return SqlRows.single(dbFile, sql, params);
    }
}
