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

    private static final List<List<Renderer.Button>> REJECT = List.of(List.of(new Renderer.Button("Татгалзах", "reject:42:1")));

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
    void downloadFileFetchesThePathGetFileNames(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        telegram.addFile("abc", "screenshot bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        api.downloadFile("abc", dir.resolve("1-photo.jpg"));

        assertEquals("screenshot bytes", java.nio.file.Files.readString(dir.resolve("1-photo.jpg")));
        assertEquals("abc", telegram.awaitRequest("getFile", Duration.ofSeconds(1)).json().get("file_id").asText());
        TelegramException missing = assertThrows(TelegramException.class, () -> api.downloadFile("nope", dir.resolve("2-file")));
        assertFalse(missing.getMessage().contains(FakeTelegram.TOKEN), missing.getMessage());
    }

    @Test
    void profilePhotoTakesTheSmallestSizeWideEnough() throws Exception {
        telegram.respond("getUserProfilePhotos", 200, "{\"ok\":true,\"result\":{\"total_count\":1,\"photos\":[["
                + "{\"file_id\":\"s\",\"width\":160},{\"file_id\":\"m\",\"width\":320},{\"file_id\":\"l\",\"width\":640}]]}}");
        telegram.addFile("m", "medium".getBytes(StandardCharsets.UTF_8));

        byte[] photo = api.profilePhoto(1, 200).orElseThrow();

        assertEquals("medium", new String(photo, StandardCharsets.UTF_8));
        assertEquals(1, telegram.awaitRequest("getUserProfilePhotos", Duration.ofSeconds(1)).json().get("user_id").asLong());
    }

    @Test
    void anEmptyEmojiClearsTheReaction() throws Exception {
        api.setMessageReaction(-100, 41, "");

        assertEquals(0, telegram.awaitRequest("setMessageReaction", Duration.ofSeconds(1)).json().get("reaction").size());
    }

    @Test
    void theMenuButtonOpensTheMiniAppOrListsTheCommands() throws Exception {
        api.setMenuButton("Удирдах", "https://dispatch.example.com");
        api.setMenuButton("Удирдах", null);

        JsonNode open = telegram.awaitRequest("setChatMenuButton", Duration.ofSeconds(1)).json().get("menu_button");
        assertEquals("web_app", open.get("type").asText());
        assertEquals("Удирдах", open.get("text").asText());
        assertEquals("https://dispatch.example.com", open.get("web_app").get("url").asText());
        JsonNode off = telegram.awaitRequest("setChatMenuButton", Duration.ofSeconds(1)).json().get("menu_button");
        assertEquals("commands", off.get("type").asText());
    }

    @Test
    void aBotWithoutAPhotoHasNone() {
        telegram.respond("getUserProfilePhotos", 200, "{\"ok\":true,\"result\":{\"total_count\":0,\"photos\":[]}}");

        assertTrue(api.profilePhoto(1, 160).isEmpty());
    }

    @Test
    void malformedTokenIsRefusedWithoutRepeatingIt() {
        // A stray space from a hand-edited secrets file; URI.create would put the whole token into its message.
        String malformed = "123456789" + ":AAH-fake token-for-tests";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> BotApi.create(malformed));

        assertTrue(error.getMessage().contains("TELEGRAM_BOT_TOKEN"), error.getMessage());
        assertFalse(error.getMessage().contains("fake token"), error.getMessage());
    }

    @Test
    void sendMessageRepliesInHtmlWithInlineButtons() throws Exception {
        long messageId = api.sendMessage(-100L, null, "<b>#42</b> план", 55L, REJECT);

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
        api.sendMessage(-100L, null, "hello", null, List.of());

        JsonNode body = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json();
        assertFalse(body.has("reply_parameters"));
        assertFalse(body.has("reply_markup"));
        assertFalse(body.has("message_thread_id"));
    }

    @Test
    void telegramErrorsCarryCodeRetryAfterAndPermanence() {
        telegram.respond("sendMessage", 429,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\",\"parameters\":{\"retry_after\":7}}");
        telegram.respond("sendMessage", 403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was kicked from the group chat\"}");

        TelegramException limited = assertThrows(TelegramException.class, () -> api.sendMessage(-100L, null, "x", null, List.of()));
        TelegramException kicked = assertThrows(TelegramException.class, () -> api.sendMessage(-100L, null, "x", null, List.of()));

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

        TelegramException error = assertThrows(TelegramException.class, () -> offline.sendMessage(-100L, null, "x", null, List.of()));

        assertFalse(error.isPermanent());
        assertFalse(String.valueOf(error.getMessage()).contains(FakeTelegram.TOKEN), error.getMessage());
    }

    @Test
    void errorDetailsNeverContainTheTokenInAnyForm() {
        String token = "7412369850:" + "AAH" + "k".repeat(32);
        URI base = URI.create("https://api.telegram.org/bot" + token + "/");

        String scrubbed = BotApi.scrub("GET https://api.telegram.org/bot" + token + "/getUpdates failed; bad token " + token, base);

        assertFalse(scrubbed.contains(token), scrubbed);
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
        long messageId = api.sendDocument(-100L, null, "plan-42.md", "# План\n1. step".getBytes(StandardCharsets.UTF_8),
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
    void privateChatMenuIsRegisteredForAllPrivateChats() throws Exception {
        api.setPrivateChatCommands(List.of(new BotApi.BotCommand("status", "Одоо юу хийж байна")));

        JsonNode body = telegram.awaitRequest("setMyCommands", Duration.ofSeconds(1)).json();
        assertEquals("all_private_chats", body.get("scope").get("type").asText());
        assertFalse(body.get("scope").has("chat_id"));
        assertEquals("status", body.get("commands").get(0).get("command").asText());
    }

    @Test
    void keyboardRowsStaySeparateRows() throws Exception {
        api.sendMessage(-100L, null, "x", null, List.of(List.of(new Renderer.Button("a", "1")),
                List.of(new Renderer.Button("b", "2"), new Renderer.Button("c", "3"))));

        JsonNode rows = telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("reply_markup").get("inline_keyboard");
        assertEquals(2, rows.size());
        assertEquals("c", rows.get(1).get(1).get("text").asText());
    }

    @Test
    void editMessageTextReplacesTheTextAndKeyboardOfASentMessage() throws Exception {
        api.editMessageText(100L, 77L, "<b>new</b>", List.of(List.of(new Renderer.Button("b", "prio:4:LOW"))));

        JsonNode body = telegram.awaitRequest("editMessageText", Duration.ofSeconds(1)).json();
        assertEquals(100, body.get("chat_id").asLong());
        assertEquals(77, body.get("message_id").asLong());
        assertEquals("<b>new</b>", body.get("text").asText());
        assertEquals("HTML", body.get("parse_mode").asText());
        assertEquals("prio:4:LOW", body.get("reply_markup").get("inline_keyboard").get(0).get(0).get("callback_data").asText());
    }

    @Test
    void topicsAreCreatedAndRenamedInAPrivateChatAndMessagesSentIntoThem() throws Exception {
        long thread = api.createForumTopic(100L, "#7 · alm · Fix login", 0xFB6F5F);
        api.sendMessage(100L, thread, "plan", null, List.of());
        api.editForumTopic(100L, thread, "✅ #7 · alm · Fix login");

        assertEquals(500, thread);
        JsonNode created = telegram.awaitRequest("createForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(100, created.get("chat_id").asLong());
        assertEquals("#7 · alm · Fix login", created.get("name").asText());
        assertEquals(0xFB6F5F, created.get("icon_color").asInt());
        assertEquals(500, telegram.awaitRequest("sendMessage", Duration.ofSeconds(1)).json().get("message_thread_id").asLong());
        JsonNode renamed = telegram.awaitRequest("editForumTopic", Duration.ofSeconds(1)).json();
        assertEquals(500, renamed.get("message_thread_id").asLong());
        assertEquals("✅ #7 · alm · Fix login", renamed.get("name").asText());
    }

    @Test
    void getMeReturnsTheBotUsername() {
        assertEquals(FakeTelegram.BOT_USERNAME, api.getMe().get("username").asText());
    }
}
