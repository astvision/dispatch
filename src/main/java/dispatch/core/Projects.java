package dispatch.core;

import dispatch.config.Config;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Configured projects, looked up by name or alias, with availability checked at the moment of use. */
public final class Projects {

    private final List<Config.Project> projects;
    private final Function<Config.Project, Optional<String>> unavailableReason;

    /** @param unavailableReason empty when the project can take tasks, otherwise why not (e.g. repo not cloned) */
    public Projects(List<Config.Project> projects, Function<Config.Project, Optional<String>> unavailableReason) {
        this.projects = List.copyOf(projects);
        this.unavailableReason = unavailableReason;
    }

    public Optional<Config.Project> find(String nameOrAlias) {
        return projects.stream()
                .filter(project -> project.name().equalsIgnoreCase(nameOrAlias)
                        || (project.alias() != null && project.alias().equalsIgnoreCase(nameOrAlias)))
                .findFirst();
    }

    /** Exact configured name, as stored on tasks; empty if the project was removed from config since. */
    public Optional<Config.Project> byName(String name) {
        return projects.stream().filter(project -> project.name().equals(name)).findFirst();
    }

    public Optional<String> unavailableReason(Config.Project project) {
        return unavailableReason.apply(project);
    }

    public List<Config.Project> all() {
        return projects;
    }
}
