package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Log;
import dispatch.cli.Locations;
import dispatch.cli.Service;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.core.TaskService;
import dispatch.domain.GroupAck;
import dispatch.store.Database;
import dispatch.store.MemberPrefs;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.ui.UiServer.Caller;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The Mini App inside {@code dispatch run} (ADR 0019): the same bundled pages {@code dispatch ui} serves, on their own
 * loopback port behind the owner's tunnel, with {@link TelegramAuth} in place of the one-time link.
 *
 * <p>Two differences from {@code dispatch ui}, both deliberate. The setup routes are never registered here — setting
 * Dispatch up from Telegram is out of scope, and a bot that is not set up yet cannot serve a page anyway. And the
 * management routes answer only an admin, because this port is on the internet.
 */
public final class MiniApp {

    private MiniApp() {
    }

    /**
     * The running server, or empty when this build has no bundled pages: a source build without Node has none, and
     * refusing to start the bot over a missing web UI would stop tasks running for the sake of a page.
     *
     * @param configFile   where the management pages read and save the config
     * @param botUsername  the bot's own @username, which the Mini App's header names
     * @param botPhoto     the bot's profile photo as a data URI ({@link #botPhoto}), null for its initials instead
     * @param resourceRoot the classpath folder of the bundled pages, "/ui" in the release jar
     */
    public static Optional<UiServer> start(Config config, Path configFile, Database db, TaskService tasks, Groups groups,
                                           String botUsername, String botPhoto, Function<String, BotApi> bots,
                                           Map<String, String> environment, Clock clock, String resourceRoot) throws IOException {
        Config.MiniApp miniApp = config.miniApp();
        if (!UiServer.hasUi(resourceRoot)) {
            Log.warn("miniapp.no_pages", "detail", "this build bundles no web UI, so the Mini App is not served; "
                    + "install the release jar to use it");
            return Optional.empty();
        }
        // Inside dispatch run: a management change the bot can take live (an unlink) goes straight to its groups.
        UiRoutes management = UiRoutes.management(configFile, Locations.current(), bots, Service.forThisMachine(),
                environment, version(), groups::replace);
        Map<String, BiFunction<Caller, JsonNode, Object>> post = new HashMap<>(management.post(true));
        post.putAll(new TasksApi(db, tasks, groups).routes());
        post.put("/api/me/prefs", (caller, body) -> setPrefs(caller, body, db, clock));
        Map<String, Function<Caller, Object>> get = new HashMap<>(management.get(true));
        get.put("/api/me", caller -> me(caller, botUsername, botPhoto));
        get.put("/api/me/prefs", caller -> prefs(caller, db));
        get.put("/api/projects", caller -> projects(caller, config.projects(), groups));
        return Optional.of(UiServer.start(miniApp.port(), resourceRoot,
                port -> new TelegramAuth(config.secrets().telegramBotToken(), () -> groups, config.isTeam(),
                        miniApp.publicUrl(), port, clock),
                Map.copyOf(get), Map.copyOf(post)));
    }

    /**
     * Who Telegram says is looking, and what they may reach. The shell asks this instead of the setup state, which is
     * what {@code dispatch ui} asks and this server does not serve.
     */
    private static Map<String, Object> me(Caller caller, String botUsername, String botPhoto) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("ref", caller.ref());
        me.put("name", caller.name());
        me.put("admin", caller.admin());
        me.put("bot", botUsername);
        me.put("botPhoto", botPhoto);
        return me;
    }

    /**
     * The bot's profile photo for the Mini App's header, read once at startup, so a new photo shows after a restart.
     * A data URI keeps it on the one authenticated /api/me answer instead of a route of its own. Null when the bot has
     * no photo or Telegram would not give it: the header falls back to initials, and nothing else depends on it.
     */
    public static String botPhoto(BotApi api, long botId) {
        try {
            return api.profilePhoto(botId, 160)
                    .map(bytes -> "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes))
                    .orElse(null);
        } catch (TelegramException e) {
            Log.warn("miniapp.bot_photo_unavailable", "detail", e.getMessage());
            return null;
        }
    }

    /** How the caller's own group hears about their task: their own choice, everyone's to read and change (G-1e). */
    private static Map<String, Object> prefs(Caller caller, Database db) {
        GroupAck pref = db.transactionReturning(tx -> MemberPrefs.groupAck(tx, userId(caller.ref())));
        return Map.of("groupAck", pref.value());
    }

    private static Map<String, Object> setPrefs(Caller caller, JsonNode body, Database db, Clock clock) {
        GroupAck value;
        try {
            value = GroupAck.fromValue(body.path("groupAck").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid", "groupAck must be one of reaction, reactionAndLine, silent");
        }
        db.transaction(tx -> MemberPrefs.setGroupAck(tx, userId(caller.ref()), value, clock.instant()));
        return Map.of("groupAck", value.value());
    }

    private static long userId(String ref) {
        return Long.parseLong(ref.substring("telegram:".length()));
    }

    /**
     * The projects of the caller's own groups, as the home screen lists them: a member cannot read the config, and
     * needs no more of it than this. They are the projects this bot was started with, which is what it takes tasks for.
     */
    private static Map<String, Object> projects(Caller caller, List<Config.Project> all, Groups groups) {
        Set<String> visible = groups.projectsOfMember(caller.ref());
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Config.Project project : all) {
            if (!visible.contains(project.name())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", project.name());
            row.put("alias", project.alias());
            row.put("baseBranch", project.baseBranch());
            listed.add(row);
        }
        return Map.of("projects", listed);
    }

    private static String version() {
        return Optional.ofNullable(MiniApp.class.getPackage().getImplementationVersion()).orElse("dev");
    }
}
