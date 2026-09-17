package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.testing.FakeTelegram;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotApiTest {

    private static final List<Renderer.Button> REJECT = List.of(new Renderer.Button("Татгалзах", "reject:42:1"));

    private FakeTelegram telegram;
    private BotApi api;

    @BeforeEach
    void start() throws Exception {
        telegram = FakeTelegram.start();
        api = new BotApi(HttpClient.newHttpClient(), telegram.baseUri(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        telegram.close();
    }

    @Test
    void sendMessageRepliesInHtmlWithInlineButtons() throws Exception {
        long messageId = api.sendMessage(-100L, "<b>#42</b> план", 55L, REJECT);

        assertEquals(1000, messageId);
        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertEquals(-100L, body.get("chat_id").asLong());
        assertEquals("<b>#42</b> план", body.get("text").asText());
        assertEquals("HTML", body.get("parse_mode").asText());
        assertEquals(55, body.get("reply_parameters").get("message_id").asLong());
        assertTrue(body.get("reply_parameters").get("allow_sending_without_reply").asBoolean());
        JsonNode button = body.get("reply_markup").get("inline_keyboard").get(0).get(0);
        assertEquals("Татгалзах", button.get("text").asText());
        assertEquals("reject:42:1", button.get("callback_data").asText());
    }

    @Test
    void messageWithoutReplyOrButtonsOmitsThem() throws Exception {
        api.sendMessage(-100L, "hello", null, List.of());

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertFalse(body.has("reply_parameters"));
        assertFalse(body.has("reply_markup"));
    }

    @Test
    void telegramErrorsCarryCodeRetryAfterAndPermanence() {
        telegram.respond("sendMessage", 429,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\",\"parameters\":{\"retry_after\":7}}");
        telegram.respond("sendMessage", 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was kicked from the group chat\"}");

        TelegramException limited = assertThrows(TelegramException.class, () -> api.sendMessage(-100L, "x", null, List.of()));
        TelegramException kicked = assertThrows(TelegramException.class, () -> api.sendMessage(-100L, "x", null, List.of()));

        assertEquals(429, limited.errorCode());
        assertEquals(7, limited.retryAfterSeconds());
        assertFalse(limited.isPermanent());
        assertEquals(403, kicked.errorCode());
        assertTrue(kicked.isPermanent());
        assertTrue(kicked.getMessage().contains("bot was kicked"), kicked.getMessage());
    }

    @Test
    void unreachableApiIsTransientAndNeverLeaksTheToken() {
        BotApi offline = new BotApi(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/bot" + FakeTelegram.TOKEN + "/"),
                Duration.ofSeconds(2));

        TelegramException error = assertThrows(TelegramException.class, () -> offline.sendMessage(-100L, "x", null, List.of()));

        assertFalse(error.isPermanent());
        assertFalse(String.valueOf(error.getMessage()).contains(FakeTelegram.TOKEN), error.getMessage());
    }

    @Test
    void getUpdatesSendsOffsetAndOnlyTheUpdateTypesDispatchHandles() throws Exception {
        telegram.pushUpdate(Json.object().put("update_id", 17));

        List<JsonNode> updates = api.getUpdates(17, 0);

        assertEquals(17, updates.getFirst().get("update_id").asLong());
        JsonNode body = telegram.awaitRequest("getUpdates", Duration.ofSeconds(1)).json();
        assertEquals(17, body.get("offset").asLong());
        assertEquals("[\"message\",\"callback_query\",\"my_chat_member\"]", body.get("allowed_updates").toString());
    }

    @Test
    void sendDocumentUploadsTheFileAsMultipartWithCaptionReplyAndButtons() throws Exception {
        long messageId = api.sendDocument(-100L, "plan-42.md", "# План\n1. step".getBytes(StandardCharsets.UTF_8),
                "📋 <b>#42</b>", 55L, REJECT);

        assertEquals(1000, messageId);
        FakeTelegram.Request request = telegram.awaitRequest("sendDocument", Duration.ofSeconds(1));
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="), request.contentType());
        String body = request.text();
        assertTrue(body.contains("filename=\"plan-42.md\""), body);
        assertTrue(body.contains("# План\n1. step"), body);
        assertTrue(body.contains("name=\"chat_id\"\r\n\r\n-100\r\n"), body);
        assertTrue(body.contains("name=\"caption\"\r\n\r\n📋 <b>#42</b>\r\n"), body);
        assertTrue(body.contains("\"callback_data\":\"reject:42:1\""), body);
    }

    @Test
    void commandMenuIsRegisteredForOneChat() throws Exception {
        api.setMyCommands(-100L, List.of(new BotApi.BotCommand("task", "Шинэ даалгавар"), new BotApi.BotCommand("help", "Тусламж")));

        JsonNode body = telegram.awaitRequest("setMyCommands", Duration.ofSeconds(1)).json();
        assertEquals("chat", body.get("scope").get("type").asText());
        assertEquals(-100L, body.get("scope").get("chat_id").asLong());
        assertEquals("task", body.get("commands").get(0).get("command").asText());
        assertEquals("Шинэ даалгавар", body.get("commands").get(0).get("description").asText());
        assertEquals(2, body.get("commands").size());
    }

    @Test
    void getMeReturnsTheBotUsername() {
        assertEquals(FakeTelegram.BOT_USERNAME, api.getMe().get("username").asText());
    }
}
