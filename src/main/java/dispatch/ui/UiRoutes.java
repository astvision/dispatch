package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.cli.Checks;
import dispatch.cli.Locations;
import dispatch.cli.Service;
import dispatch.telegram.BotApi;
import dispatch.ui.UiServer.Caller;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The management pages' routes, built once and registered by both servers that serve them: {@code dispatch ui}
 * (UI-3a) and the Mini App inside {@code dispatch run} (UI-3b). Only the Mini App gates them on being an admin;
 * whoever holds {@code dispatch ui}'s link is the owner already.
 */
public final class UiRoutes {

    static final String NOT_ADMIN = "only an admin may manage Dispatch; ask one of them, or use dispatch ui on the machine";

    private final OverviewApi overview;
    private final ManageApi manage;

    private UiRoutes(OverviewApi overview, ManageApi manage) {
        this.overview = overview;
        this.manage = manage;
    }

    /** @param version what the Overview shows as this build's version */
    public static UiRoutes management(Path configFile, Locations locations, Function<String, BotApi> bots, Service service,
                                      Map<String, String> environment, String version) {
        return new UiRoutes(new OverviewApi(configFile, locations, new Checks(bots), service, environment, version),
                new ManageApi(configFile, service, environment));
    }

    /** @param adminsOnly whether a caller who may not manage is refused; false for {@code dispatch ui} */
    public Map<String, Function<Caller, Object>> get(boolean adminsOnly) {
        return Map.of("/api/overview", guard(adminsOnly, caller -> overview.get()));
    }

    /** @param adminsOnly likewise */
    public Map<String, BiFunction<Caller, JsonNode, Object>> post(boolean adminsOnly) {
        return anyCaller(manage.routes(), adminsOnly);
    }

    /**
     * Lifts routes that do not care who is asking. The management API answers the same whoever reaches it; who may
     * reach it at all is this server's question, not that API's, so the check sits here instead of in every route.
     */
    static Map<String, BiFunction<Caller, JsonNode, Object>> anyCaller(Map<String, Function<JsonNode, Object>> routes,
                                                                      boolean adminsOnly) {
        Map<String, BiFunction<Caller, JsonNode, Object>> lifted = new HashMap<>();
        routes.forEach((path, route) -> lifted.put(path, (caller, body) -> {
            requireAdmin(adminsOnly, caller);
            return route.apply(body);
        }));
        return Map.copyOf(lifted);
    }

    private static Function<Caller, Object> guard(boolean adminsOnly, Function<Caller, Object> route) {
        return caller -> {
            requireAdmin(adminsOnly, caller);
            return route.apply(caller);
        };
    }

    private static void requireAdmin(boolean adminsOnly, Caller caller) {
        if (adminsOnly && !caller.admin()) {
            throw new ApiException(403, "not_admin", NOT_ADMIN);
        }
    }
}
