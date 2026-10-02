package dispatch.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The official plugins a plan may pick for its execution (spec: plugin picks, ADR 0037): hosted in Anthropic's
 * claude-plugins-official marketplace, and fit for an unattended, sandboxed run. None has a hook that waits for a person
 * or calls a model of its own, none needs credentials, and none needs a tool Dispatch's runs lack.
 */
public final class CuratedPlugins {

    /** The marketplace every entry comes from, as Claude Code names it. */
    public static final String MARKETPLACE = "claude-plugins-official";

    /** @param when when a plan should pick it, as the plan prompt says it */
    public record Entry(String name, String when) {
    }

    public static final List<Entry> ALL = List.of(
            new Entry("frontend-design", "the task builds or reshapes a user interface"),
            new Entry("playwright", "the change should be checked in a real browser"),
            new Entry("context7", "the task depends on a library's current API or configuration"));

    private CuratedPlugins() {
    }

    /**
     * The listed name {@code given} means, ignoring case, surrounding spaces and an {@code @claude-plugins-official}
     * suffix; empty when it names nothing on the list.
     */
    public static Optional<String> match(String given) {
        String name = given.strip().toLowerCase(Locale.ROOT);
        String suffix = "@" + MARKETPLACE;
        String bare = name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
        return ALL.stream().map(Entry::name).filter(bare::equals).findFirst();
    }
}
