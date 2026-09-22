package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.cli.CliException;
import dispatch.cli.Locations;
import dispatch.cli.ProjectAddCommand;
import dispatch.cli.ProjectProbe;
import dispatch.cli.SecretsFile;
import dispatch.cli.Service;
import dispatch.cli.ServiceCommand;
import dispatch.cli.Setup;
import dispatch.config.Config;
import dispatch.telegram.BotApi;
import dispatch.telegram.TelegramException;
import dispatch.workspace.Git;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Setup in the browser (spec: Screens and data flow, Setup): the steps of dispatch init as calls the page makes one after
 * the other. What was answered stays in this process until Write, as with init: nothing is written before. dispatch ui
 * serves one person, so there is one setup at a time.
 */
public final class SetupApi {

    /** Under browsers' and proxies' idle limits; the page asks again right away. */
    static final Duration POLL = Duration.ofSeconds(25);
    private static final Set<String> MODELS = Set.of("sonnet", "opus", "fable");
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public record Person(long id, String name) {
    }

    public record Group(long id, String title) {
    }

    public record BotView(String username, boolean topicsEnabled) {
    }

    /** Everything the page needs to show the current step; never the bot token. */
    public record State(boolean configExists, String configFile, boolean team, BotView bot, List<Person> members,
                        Person candidate, Group group, String claudeFound, String authorEmail, List<String> hints,
                        int hintAfterSeconds) {
    }

    public record Candidate(Person candidate) {
    }

    public record GroupFound(Group group) {
    }

    public record ClaudeVersion(String command, String version) {
    }

    public record ProjectView(String folder, String name, String originUrl, boolean originHadCredentials, String baseBranch) {
    }

    public record Written(String configFile, String secretsFile) {
    }

    private final Path configFile;
    private final Locations locations;
    private final Function<String, BotApi> bots;
    private final Service service;
    private final Path jar;
    private final Map<String, String> env;
    private final Duration poll;
    private final Git git = new Git("git", null, Duration.ofSeconds(30));
    /** Telegram is read for one request at a time; a person found stays pending, so an abandoned request loses nobody. */
    private final Object reading = new Object();

    // Guarded by this.
    private boolean team;
    private Setup.Bot bot;
    private Setup.Updates updates;
    private final List<Config.Member> members = new ArrayList<>();
    private final List<Config.Member> declined = new ArrayList<>();
    private Config.Member candidate;
    private Setup.Chat chat;

    /** @param jar the dispatch.jar the background service runs, null when not running from one */
    public SetupApi(Path configFile, Locations locations, Function<String, BotApi> bots, Service service, Path jar,
                    Map<String, String> processEnvironment) {
        this(configFile, locations, bots, service, jar, processEnvironment, POLL);
    }

    SetupApi(Path configFile, Locations locations, Function<String, BotApi> bots, Service service, Path jar,
             Map<String, String> processEnvironment, Duration poll) {
        this.configFile = configFile.toAbsolutePath();
        this.locations = locations;
        this.bots = bots;
        this.service = service;
        this.jar = jar;
        this.env = processEnvironment;
        this.poll = poll;
    }

    public Map<String, Function<JsonNode, Object>> routes() {
        return Map.ofEntries(
                Map.entry("/api/setup/state", body -> state()),
                Map.entry("/api/setup/team", this::team),
                Map.entry("/api/setup/token", this::token),
                Map.entry("/api/setup/people/next", body -> nextPerson()),
                Map.entry("/api/setup/people/answer", this::answer),
                Map.entry("/api/setup/group/next", body -> nextGroup()),
                Map.entry("/api/setup/claude", this::claude),
                Map.entry("/api/setup/folders", body -> Folders.list(optionalText(body, "path"), Path.of(System.getProperty("user.home")))),
                Map.entry("/api/setup/project", this::project),
                Map.entry("/api/setup/write", this::write),
                Map.entry("/api/service/install", body -> installService()),
                Map.entry("/api/service/stop", body -> stopService()));
    }

    synchronized State state() {
        return new State(Files.exists(configFile), configFile.toString(), team,
                bot == null ? null : new BotView(bot.username(), bot.topicsEnabled()),
                members.stream().map(SetupApi::person).toList(), candidate == null ? null : person(candidate),
                chat == null ? null : new Group(chat.id(), chat.title()),
                Setup.findClaude(env).map(Path::toString).orElse(null), Setup.gitEmail().orElse(null),
                Setup.QUIET_HINTS, (int) Setup.HINT_AFTER.toSeconds());
    }

    private synchronized State team(JsonNode body) {
        team = requiredBoolean(body, "team");
        return state();
    }

    private BotView token(JsonNode body) {
        Setup.Bot checked = Setup.bot(text(body, "token"), bots); // outside the lock: it asks Telegram
        synchronized (this) {
            bot = checked;
            updates = new Setup.Updates(checked.api());
            members.clear();
            declined.clear();
            candidate = null;
            chat = null;
        }
        return new BotView(checked.username(), checked.topicsEnabled());
    }

    private Candidate nextPerson() {
        synchronized (reading) {
            Setup.Updates source;
            synchronized (this) {
                if (candidate != null) {
                    return new Candidate(person(candidate));
                }
                // Someone declined as you, e.g. a teammate who pressed Start first, may still join the team.
                Optional<Config.Member> earlier = members.isEmpty() ? Optional.empty() : declined.stream().findFirst();
                if (earlier.isPresent()) {
                    candidate = earlier.get();
                    declined.remove(candidate);
                    return new Candidate(person(candidate));
                }
                source = requireUpdates();
            }
            Optional<JsonNode> update = read(source, found -> Setup.isPrivateMessage(found) && !known(Setup.member(found)));
            if (update.isEmpty()) {
                return new Candidate(null);
            }
            Config.Member found = Setup.member(update.get());
            synchronized (this) {
                // The token may have changed while this call was polling; a person found by the OLD bot must never
                // become a candidate for the new one.
                if (updates != source) {
                    return new Candidate(null);
                }
                candidate = found;
            }
            greet(source, found.id());
            return new Candidate(person(found));
        }
    }

    private synchronized State answer(JsonNode body) {
        long id = requiredLong(body, "id");
        boolean accept = requiredBoolean(body, "accept");
        if (candidate == null || candidate.id() != id) {
            throw new CliException("that person is no longer waiting; wait for the next one");
        }
        // Granting access is always this explicit answer; nothing is accepted by default.
        (accept ? members : declined).add(candidate);
        candidate = null;
        return state();
    }

    private GroupFound nextGroup() {
        synchronized (reading) {
            Setup.Updates source;
            synchronized (this) {
                if (!team) {
                    throw new CliException("only a team's bot has a group");
                }
                if (chat != null) {
                    return new GroupFound(new Group(chat.id(), chat.title()));
                }
                source = requireUpdates();
            }
            Optional<JsonNode> update = read(source, found -> Setup.group(found) != null);
            if (update.isEmpty()) {
                return new GroupFound(null);
            }
            JsonNode found = Setup.group(update.get());
            synchronized (this) {
                // Same guard as nextPerson: a group found by the OLD bot must never become the new one's group.
                if (updates != source) {
                    return new GroupFound(null);
                }
                chat = new Setup.Chat(found.path("id").asLong(), found.path("title").asText("group"));
                return new GroupFound(new Group(chat.id(), chat.title()));
            }
        }
    }

    private ClaudeVersion claude(JsonNode body) {
        String command = text(body, "command");
        String version = Setup.claudeVersion(command).orElseThrow(() -> new CliException(
                "cannot run " + command + " --version; install Claude Code, or give the full path to claude"));
        return new ClaudeVersion(command, version);
    }

    private ProjectView project(JsonNode body) {
        ProjectProbe probe = probe(text(body, "folder"));
        return new ProjectView(probe.folder().toString(), probe.defaultName(), probe.originUrl(), probe.originHadCredentials(),
                probe.defaultBranch());
    }

    private Written write(JsonNode body) {
        // Setup.Updates is not thread-safe (one wait at a time); acknowledge() below must never run alongside an
        // in-flight nextPerson/nextGroup poll on the same instance, or two concurrent getUpdates make Telegram
        // answer 409. Taking "reading" first, in the same order as the read paths, avoids a lock-order inversion;
        // it just means write waits for an abandoned long poll to end (at most one poll).
        synchronized (reading) {
            synchronized (this) {
                if (Files.exists(configFile)) {
                    throw new CliException(configFile + " already exists; nothing was written");
                }
                if (bot == null) {
                    throw new CliException("check the bot token first");
                }
                if (members.isEmpty()) {
                    throw new CliException("confirm who you are first");
                }
                List<ProjectAddCommand.Project> projects = projects(body.path("projects"));
                String name = Setup.teamName(team ? text(body, "teamName") : members.getFirst().name().split("\\s+")[0]);
                Setup.Answers answers = new Setup.Answers(name, team, List.copyOf(members), team ? chat : null, text(body, "claude"),
                        projects, text(body, "authorName"), text(body, "authorEmail"));
                Setup.write(configFile, Setup.render(answers, locations.stateDir()), bot.token());
                try {
                    updates.acknowledge();
                } catch (TelegramException e) {
                    System.err.println("dispatch ui: could not mark setup's messages as read (" + e.getMessage()
                            + "); the bot may answer them once it runs");
                }
                return new Written(configFile.toString(), SecretsFile.beside(configFile).toString());
            }
        }
    }

    private OverviewApi.ServiceView installService() {
        service.install(ServiceCommand.specFor(jar, configFile, env));
        return serviceView();
    }

    private OverviewApi.ServiceView stopService() {
        service.stop();
        return serviceView();
    }

    private OverviewApi.ServiceView serviceView() {
        Service.Status status = service.status();
        return new OverviewApi.ServiceView(service.describe(), status.installed(), status.running(), status.detail(), status.notes());
    }

    /** The projects the page chose; each clone is read again here, so origin comes from git, not from the page. */
    private List<ProjectAddCommand.Project> projects(JsonNode array) {
        List<ProjectAddCommand.Project> projects = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : array) {
            ProjectProbe probe = probe(text(item, "folder"));
            String name = text(item, "name");
            if (!names.add(name.toLowerCase())) {
                throw new CliException("two projects are named " + name);
            }
            String model = choice(item, "model", MODELS);
            String effort = choice(item, "effort", EFFORTS);
            projects.add(new ProjectAddCommand.Project(name, null, probe.folder(), probe.originUrl(), text(item, "baseBranch"),
                    "claude-code", model, effort));
        }
        if (projects.isEmpty()) {
            throw new CliException("add at least one project");
        }
        return projects;
    }

    private ProjectProbe probe(String folder) {
        try {
            return ProjectProbe.of(Path.of(folder), git);
        } catch (InvalidPathException e) {
            throw new CliException(folder + " is not a folder");
        }
    }

    private Optional<JsonNode> read(Setup.Updates source, Predicate<JsonNode> wanted) {
        try {
            return source.next(wanted, poll);
        } catch (Setup.ConflictException e) {
            throw new ApiException(409, "conflict", e.getMessage());
        }
    }

    private static void greet(Setup.Updates source, long chatId) {
        try {
            source.greet(chatId);
        } catch (TelegramException e) {
            System.err.println("dispatch ui: could not answer " + chatId + " in Telegram (" + e.getMessage() + ")");
        }
    }

    private synchronized boolean known(Config.Member member) {
        return members.stream().anyMatch(m -> m.id() == member.id()) || declined.stream().anyMatch(m -> m.id() == member.id());
    }

    private Setup.Updates requireUpdates() {
        if (updates == null) {
            throw new CliException("check the bot token first");
        }
        return updates;
    }

    private static Person person(Config.Member member) {
        return new Person(member.id(), member.name());
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new CliException(field + " is needed");
        }
        return value.asText().strip();
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private static String choice(JsonNode body, String field, Set<String> allowed) {
        String value = optionalText(body, field);
        if (value != null && !allowed.contains(value)) {
            throw new CliException(field + " must be one of " + allowed.stream().sorted().toList() + ", or left out");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode body, String field) {
        if (!body.path(field).isBoolean()) {
            throw new CliException(field + " must be true or false");
        }
        return body.path(field).asBoolean();
    }

    private static long requiredLong(JsonNode body, String field) {
        if (!body.path(field).isIntegralNumber()) {
            throw new CliException(field + " must be a number");
        }
        return body.path(field).asLong();
    }
}
