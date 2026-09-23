package dispatch.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The few Bot API methods Dispatch uses, over plain HTTP + JSON. The base URI embeds the bot token, so no message
 * built here ever contains the URI.
 */
public final class BotApi {

    private static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query", "my_chat_member");

    private final HttpClient http;
    private final URI baseUri;
    private final Duration requestTimeout;

    /** @param baseUri e.g. https://api.telegram.org/bot&lt;token&gt;/ (with the trailing slash) */
    public BotApi(HttpClient http, URI baseUri, Duration requestTimeout) {
        this.http = http;
        this.baseUri = baseUri;
        this.requestTimeout = requestTimeout;
    }

    /** Digits, a colon, then letters, digits, '_' and '-', as @BotFather gives them. */
    public static boolean isBotToken(String token) {
        return token != null && token.matches("\\d+:[A-Za-z0-9_-]+");
    }

    /**
     * @throws IllegalArgumentException when {@code token} cannot be a bot token. Checked first because the URI built from it
     *                                  would otherwise fail with the whole token in its message.
     */
    public static BotApi create(String token) {
        if (!isBotToken(token)) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN is not a bot token: @BotFather gives digits, a colon, then letters, "
                    + "digits, '_' and '-'");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new BotApi(http, URI.create("https://api.telegram.org/bot" + token + "/"), Duration.ofSeconds(30));
    }

    public JsonNode getMe() {
        return call("getMe", Json.object(), requestTimeout);
    }

    /** Long-polls for up to {@code timeoutSeconds}; updates below {@code offset} are confirmed and dropped by Telegram. */
    public List<JsonNode> getUpdates(long offset, int timeoutSeconds) {
        ObjectNode body = Json.object().put("offset", offset).put("timeout", timeoutSeconds);
        ArrayNode types = body.putArray("allowed_updates");
        ALLOWED_UPDATES.forEach(types::add);
        List<JsonNode> updates = new ArrayList<>();
        call("getUpdates", body, requestTimeout.plusSeconds(timeoutSeconds)).forEach(updates::add);
        return updates;
    }

    /**
     * @param threadId the topic to post in, null for none
     * @return the sent message's id
     */
    public long sendMessage(long chatId, Long threadId, String html, Long replyToMessageId, List<List<Renderer.Button>> buttons) {
        ObjectNode body = Json.object().put("chat_id", chatId).put("text", html).put("parse_mode", "HTML");
        if (threadId != null) {
            body.put("message_thread_id", threadId);
        }
        body.putObject("link_preview_options").put("is_disabled", true);
        if (replyToMessageId != null) {
            body.set("reply_parameters", replyParameters(replyToMessageId));
        }
        if (!buttons.isEmpty()) {
            body.set("reply_markup", keyboard(buttons));
        }
        return call("sendMessage", body, requestTimeout).path("message_id").asLong();
    }

    /** @return the sent message's id */
    public long sendDocument(long chatId, Long threadId, String fileName, byte[] content, String captionHtml, Long replyToMessageId,
                             List<List<Renderer.Button>> buttons) {
        String boundary = "dispatch-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "chat_id", Long.toString(chatId));
        if (threadId != null) {
            field(body, boundary, "message_thread_id", Long.toString(threadId));
        }
        field(body, boundary, "caption", captionHtml);
        field(body, boundary, "parse_mode", "HTML");
        if (replyToMessageId != null) {
            field(body, boundary, "reply_parameters", replyParameters(replyToMessageId).toString());
        }
        if (!buttons.isEmpty()) {
            field(body, boundary, "reply_markup", keyboard(buttons).toString());
        }
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/markdown; charset=utf-8\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n--" + boundary + "--\r\n");
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("sendDocument"))
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send("sendDocument", request).path("message_id").asLong();
    }

    /** Replaces a sent message's text and buttons, e.g. a status report after one of its buttons was used. */
    public void editMessageText(long chatId, long messageId, String html, List<List<Renderer.Button>> buttons) {
        ObjectNode body = Json.object().put("chat_id", chatId).put("message_id", messageId).put("text", html).put("parse_mode", "HTML");
        body.putObject("link_preview_options").put("is_disabled", true);
        body.set("reply_markup", keyboard(buttons));
        call("editMessageText", body, requestTimeout);
    }

    /** Opens a topic in a forum or in a private chat with topics on; returns its thread id. */
    public long createForumTopic(long chatId, String name, int iconColor) {
        ObjectNode body = Json.object().put("chat_id", chatId).put("name", name).put("icon_color", iconColor);
        return call("createForumTopic", body, requestTimeout).path("message_thread_id").asLong();
    }

    public void editForumTopic(long chatId, long threadId, String name) {
        call("editForumTopic", Json.object().put("chat_id", chatId).put("message_thread_id", threadId).put("name", name), requestTimeout);
    }

    public record BotCommand(String command, String description) {
    }

    /**
     * Sets the command menu shown in one chat. With privacy mode on, a group only delivers "/task@this_bot" reliably;
     * a bare "/task" reaches this bot only if it was the last bot to post there, and picking from the menu avoids that.
     */
    public void setMyCommands(long chatId, List<BotCommand> commands) {
        setMyCommands(Json.object().put("type", "chat").put("chat_id", chatId), commands);
    }

    /** Sets the command menu of every private chat with the bot. */
    public void setPrivateChatCommands(List<BotCommand> commands) {
        setMyCommands(Json.object().put("type", "all_private_chats"), commands);
    }

    /**
     * Puts a "Manage" Web App button in one private chat's menu, where the paperclip menu usually is (ADR 0019).
     * Everyone else keeps the default menu, so nobody who may not use the Mini App is offered it.
     */
    public void setChatMenuButton(long chatId, String text, String url) {
        ObjectNode button = Json.object().put("type", "web_app").put("text", text);
        button.putObject("web_app").put("url", url);
        ObjectNode body = Json.object().put("chat_id", chatId);
        body.set("menu_button", button);
        call("setChatMenuButton", body, requestTimeout);
    }

    private void setMyCommands(ObjectNode scope, List<BotCommand> commands) {
        ObjectNode body = Json.object();
        ArrayNode listed = body.putArray("commands");
        commands.forEach(command -> listed.addObject().put("command", command.command()).put("description", command.description()));
        body.set("scope", scope);
        call("setMyCommands", body, requestTimeout);
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        call("answerCallbackQuery", Json.object().put("callback_query_id", callbackQueryId).put("text", text), requestTimeout);
    }

    /** Downloads a file someone sent, e.g. a task's screenshot; Telegram serves files up to 20 MB. */
    public void downloadFile(String fileId, Path target) {
        String filePath = call("getFile", Json.object().put("file_id", fileId), requestTimeout).path("file_path").asText("");
        if (filePath.isEmpty()) {
            throw new TelegramException("getFile returned no file_path", 0, null);
        }
        // Files live beside the methods: /file/bot<token>/<path> instead of /bot<token>/<method>.
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/file" + baseUri.getPath() + filePath))
                .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<Path> response;
        try {
            // Truncated: a retry writes over what an interrupted download left.
            response = http.send(request, HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING));
        } catch (IOException e) {
            throw new TelegramException("file download failed: " + e.getClass().getSimpleName() + ": " + scrub(e.getMessage(), baseUri), 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("file download interrupted", 0, null);
        }
        if (response.statusCode() != 200) {
            throw new TelegramException("file download failed: HTTP " + response.statusCode(), response.statusCode(), null);
        }
    }

    public void leaveChat(long chatId) {
        call("leaveChat", Json.object().put("chat_id", chatId), requestTimeout);
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return send(method, request);
    }

    private JsonNode send(String method, HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TelegramException(method + " failed: " + e.getClass().getSimpleName() + ": " + scrub(e.getMessage(), baseUri), 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(method + " interrupted", 0, null);
        }
        JsonNode json;
        try {
            json = Json.MAPPER.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new TelegramException(method + " failed: HTTP " + response.statusCode() + " without a JSON body",
                    response.statusCode() >= 500 ? 0 : response.statusCode(), null);
        }
        if (json.path("ok").asBoolean(false)) {
            return json.path("result");
        }
        int code = json.path("error_code").asInt(response.statusCode());
        JsonNode retryAfter = json.path("parameters").path("retry_after");
        throw new TelegramException(method + " failed: " + code + " " + json.path("description").asText(), code,
                retryAfter.isNumber() ? retryAfter.asInt() : null);
    }

    /** Removes the bot token wherever it appears, not only inside the URL path. */
    static String scrub(String message, URI baseUri) {
        if (message == null) {
            return "no detail";
        }
        String path = baseUri.getPath();
        if (!path.startsWith("/bot")) {
            return message;
        }
        String token = path.substring("/bot".length(), path.endsWith("/") ? path.length() - 1 : path.length());
        return token.isEmpty() ? message : message.replace(token, "***");
    }

    private static ObjectNode replyParameters(long messageId) {
        return Json.object().put("message_id", messageId).put("allow_sending_without_reply", true);
    }

    private static ObjectNode keyboard(List<List<Renderer.Button>> rows) {
        ObjectNode markup = Json.object();
        ArrayNode keyboard = markup.putArray("inline_keyboard");
        for (List<Renderer.Button> buttons : rows) {
            ArrayNode row = keyboard.addArray();
            for (Renderer.Button button : buttons) {
                ObjectNode entry = row.addObject().put("text", button.text());
                if (button.webAppUrl() != null) {
                    entry.putObject("web_app").put("url", button.webAppUrl());
                } else {
                    entry.put("callback_data", button.data());
                }
            }
        }
        return markup;
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
