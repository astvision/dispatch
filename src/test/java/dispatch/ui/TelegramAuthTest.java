package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.core.Groups;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The launch data is signed the way Telegram documents it (core.telegram.org/bots/webapps, "Validating data received
 * via the Mini App"): the secret key is HMAC-SHA256 of the bot token under the literal key "WebAppData", and the hash
 * is HMAC-SHA256 of the data-check string under that secret. This test signs its own data with that algorithm written
 * out separately from {@link TelegramAuth}, so a mistake in either side shows up as a mismatch here.
 */
class TelegramAuthTest {

    private static final String TOKEN = "123456:TEST-BOT-TOKEN";
    private static final String PUBLIC_URL = "https://dispatch.example.com";
    private static final int PORT = 7879;
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final String HOST = "dispatch.example.com";

    private static final Config.Telegram TEAM = new Config.Telegram(List.of(100L),
            List.of(new Config.Group("backend", -1001234567890L,
                    List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    private static final Config.Telegram PERSONAL = new Config.Telegram(List.of(),
            List.of(new Config.Group("bold", null, List.of(new Config.Member(100, "Bold")), List.of("alm"))));

    @Test
    void signedLaunchDataNamesTheMemberWhoOpenedIt() {
        UiServer.Caller caller = team().verify(header(initData(200, NOW)), HOST);

        assertEquals("telegram:200", caller.ref());
        assertEquals("Ali", caller.name());
        assertFalse(caller.admin(), "a member of a team is not an admin");
    }

    @Test
    void aTeamsAdminMayManage() {
        assertTrue(team().verify(header(initData(100, NOW)), HOST).admin());
    }

    @Test
    void aPersonalBotsOwnerMayManage() {
        // A personal bot has no admins list at all, so isAdmin() alone would lock its owner out of their own Dispatch.
        UiServer.Caller caller = auth(PERSONAL, false).verify(header(initData(100, NOW)), HOST);

        assertTrue(caller.admin());
    }

    @Test
    void aForgedOrChangedSignatureIsRefused() {
        String signed = initData(200, NOW);

        assertEquals("unauthorized", refusal(() -> team().verify(header(signed.replace("Ali", "Eve")), HOST)).code());
        assertEquals("unauthorized", refusal(() -> team().verify(header(signed.replaceAll("hash=[0-9a-f]+", "hash=" + "0".repeat(64))), HOST)).code());
        assertEquals("unauthorized", refusal(() -> team().verify(header(signed.replaceAll("&hash=[0-9a-f]+", "")), HOST)).code());
    }

    @Test
    void aMissingOrMalformedHeaderIsRefused() {
        assertEquals("unauthorized", refusal(() -> team().verify(null, HOST)).code());
        assertEquals("unauthorized", refusal(() -> team().verify("Bearer something", HOST)).code());
        assertEquals("unauthorized", refusal(() -> team().verify("tma ", HOST)).code());
    }

    @Test
    void launchDataOlderThanAnHourAsksToReopenTheMiniApp() {
        ApiException old = refusal(() -> team().verify(header(initData(200, NOW.minus(Duration.ofHours(2)))), HOST));
        ApiException ahead = refusal(() -> team().verify(header(initData(200, NOW.plus(Duration.ofMinutes(5)))), HOST));

        assertEquals(401, old.status());
        assertEquals("expired", old.code());
        assertEquals("expired", ahead.code(), "a clock far ahead is as suspect as one far behind");
        assertEquals("telegram:200", team().verify(header(initData(200, NOW.minus(Duration.ofMinutes(30)))), HOST).ref(),
                "half an hour old is still fresh");
    }

    @Test
    void someoneInNoGroupIsToldToAskAnAdmin() {
        ApiException refused = refusal(() -> team().verify(header(initData(999, NOW)), HOST));

        assertEquals(403, refused.status());
        assertEquals("not_a_member", refused.code());
        assertTrue(refused.getMessage().contains("admin"), refused.getMessage());
    }

    @Test
    void onlyTheConfiguredHostAndTheLoopbackPortAreAnswered() {
        assertEquals("telegram:200", team().verify(header(initData(200, NOW)), "127.0.0.1:" + PORT).ref(),
                "the tunnel forwards to the loopback port, and dispatch check probes it there");
        assertEquals("host", refusal(() -> team().verify(header(initData(200, NOW)), "attacker.example")).code());
        assertEquals("host", refusal(() -> team().verify(header(initData(200, NOW)), null)).code());
    }

    @Test
    void aMemberWhoJoinedSinceStartIsLetIn() {
        Groups groups = new Groups(PERSONAL);
        TelegramAuth auth = new TelegramAuth(TOKEN, () -> groups, false, PUBLIC_URL, PORT, clock());

        assertEquals("not_a_member", refusal(() -> auth.verify(header(initData(300, NOW)), HOST)).code());

        groups.replace(new Config.Telegram(List.of(),
                List.of(new Config.Group("bold", null,
                        List.of(new Config.Member(100, "Bold"), new Config.Member(300, "New")), List.of("alm")))));

        assertEquals("telegram:300", auth.verify(header(initData(300, NOW)), HOST).ref(),
                "membership changes in-process when someone joins; the Mini App must not need a restart");
    }

    @Test
    void theMiniAppIsFramedByTelegramAndNeverAsksForAPageToBeProved() {
        assertTrue(team().pageRefusal(null).isEmpty(), "the page loads before the script that reads the launch data");
    }

    private TelegramAuth team() {
        return auth(TEAM, true);
    }

    private TelegramAuth auth(Config.Telegram telegram, boolean team) {
        return new TelegramAuth(TOKEN, () -> new Groups(telegram), team, PUBLIC_URL, PORT, clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String header(String initData) {
        return "tma " + initData;
    }

    private static ApiException refusal(Runnable call) {
        return assertThrows(ApiException.class, call::run);
    }

    /** Launch data as Telegram sends it: a URL-encoded query string, signed with the documented algorithm. */
    private static String initData(long userId, Instant authDate) {
        Map<String, String> fields = new TreeMap<>(Map.of(
                "auth_date", String.valueOf(authDate.getEpochSecond()),
                "query_id", "AAHdF6IQAAAAAN0XohDhrOrc",
                "user", "{\"id\":" + userId + ",\"first_name\":\"" + name(userId) + "\",\"language_code\":\"mn\"}"));
        List<String> checked = new ArrayList<>();
        fields.forEach((key, value) -> checked.add(key + "=" + value));
        String hash = hex(hmac(hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN), String.join("\n", checked)));
        List<String> encoded = new ArrayList<>();
        fields.forEach((key, value) -> encoded.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", encoded) + "&hash=" + hash;
    }

    private static String name(long userId) {
        return userId == 100 ? "Bold" : userId == 200 ? "Ali" : "Someone";
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
