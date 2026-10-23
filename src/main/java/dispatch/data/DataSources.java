package dispatch.data;

import dispatch.config.Config;
import dispatch.config.DataSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The projects' prod Mongo this machine may reach for reports, and the tunnel a run opens to one (ADR 0043). */
public interface DataSources {

    /** No project has data: a team instance and a member's computer (data is personal-only). */
    DataSources NONE = new DataSources() {
        @Override
        public Optional<DataSource> forProject(String project) {
            return Optional.empty();
        }

        @Override
        public Tunnel open(DataSource source) {
            throw new IllegalStateException("no project has data here");
        }
    };

    Optional<DataSource> forProject(String project);

    /** @throws TunnelException when the tunnel does not forward; nothing is left running then */
    Tunnel open(DataSource source);

    /**
     * {@code environment} without the variables that hold a project's data URI. dispatch.env is the agents' environment too, so
     * without this every run, of any project, would carry prod's real address and read-only password; a data run gets its
     * tunnelled URI as {@code DISPATCH_DATA_URI} instead (ADR 0043).
     */
    static Map<String, String> withoutDataUris(Map<String, String> environment, Config config) {
        java.util.Set<String> uris = new java.util.HashSet<>();
        for (Config.Project project : config.projects()) {
            if (project.data() != null) {
                uris.add(project.data().uri());
            }
        }
        Map<String, String> kept = new HashMap<>(environment);
        kept.values().removeIf(uris::contains);
        return kept;
    }

    static DataSources of(Config config) {
        return of(config, List.of("ssh"));
    }

    /** @param ssh the ssh client command; the live test passes its own */
    static DataSources of(Config config, List<String> ssh) {
        Map<String, DataSource> byProject = new HashMap<>();
        for (Config.Project project : config.projects()) {
            if (project.data() != null) {
                byProject.put(project.name(), project.data());
            }
        }
        Map<String, DataSource> sources = Map.copyOf(byProject);
        List<String> command = List.copyOf(ssh);
        return new DataSources() {
            @Override
            public Optional<DataSource> forProject(String project) {
                return Optional.ofNullable(sources.get(project));
            }

            @Override
            public Tunnel open(DataSource source) {
                return Tunnel.open(command, source, Duration.ofSeconds(20));
            }
        };
    }
}
