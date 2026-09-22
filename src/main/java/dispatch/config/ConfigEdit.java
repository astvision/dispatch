package dispatch.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Targeted edits of a config file's text, for the web UI's management pages: set a value, remove a key or a list item,
 * append to a list. As in {@link ConfigText}, SnakeYAML's node marks say where things are and only those lines change,
 * so comments, order, spacing and line endings stay as written. Structural edits need block style; a flow collection
 * ({@code {...}} or {@code [...]}) is refused with a message, since editing inside one in place is error-prone.
 */
public final class ConfigEdit {

    /** Plain words YAML 1.1 reads as null or a boolean; typed as a value, they are quoted so they stay text. */
    private static final Set<String> RESERVED = Set.of("null", "~", "true", "false", "yes", "no", "on", "off", "y", "n");

    private ConfigEdit() {
    }

    /** One step from the top of the config down to a value. */
    public sealed interface Step permits Key, Item, Value {
    }

    /** A key of a mapping. */
    public record Key(String name) implements Step {
    }

    /** The item of a list of mappings whose {@code field} is {@code value}, e.g. the project named alm. */
    public record Item(String field, String value) implements Step {
    }

    /** The item of a list of plain values that equals {@code value}, e.g. an admin's id. */
    public record Value(String value) implements Step {
    }

    /** Where an edit applies, e.g. {@code At.of("projects").item("name", "alm").key("plan", "model")}. */
    public record At(List<Step> steps) {

        public At {
            steps = List.copyOf(steps);
        }

        public static At of(String... keys) {
            return new At(List.of()).key(keys);
        }

        public At key(String... keys) {
            List<Step> more = new ArrayList<>(steps);
            for (String key : keys) {
                more.add(new Key(key));
            }
            return new At(more);
        }

        public At item(String field, String value) {
            List<Step> more = new ArrayList<>(steps);
            more.add(new Item(field, value));
            return new At(more);
        }

        public At value(String value) {
            List<Step> more = new ArrayList<>(steps);
            more.add(new Value(value));
            return new At(more);
        }

        /** The first {@code count} steps as the config's readers write them, e.g. "telegram.groups[name=bold].projects[alm]". */
        String describe(int count) {
            StringBuilder out = new StringBuilder();
            for (Step step : steps.subList(0, count)) {
                switch (step) {
                    case Key key -> out.append(out.isEmpty() ? "" : ".").append(key.name());
                    case Item item -> out.append('[').append(item.field()).append('=').append(item.value()).append(']');
                    case Value value -> out.append('[').append(value.value()).append(']');
                }
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return describe(steps.size());
        }
    }

    /**
     * Sets the value at {@code at}, which ends with a key. A missing key is added after the last line of its mapping, and
     * missing mappings above it are created, e.g. a project's {@code plan:} block.
     *
     * @param value the value as typed; it is written quoted where YAML would read it differently
     */
    public static String set(String text, At at, String value) {
        if (at.steps().isEmpty() || !(at.steps().getLast() instanceof Key)) {
            throw new IllegalArgumentException("set needs a key at the end, got " + at);
        }
        String scalar = scalar(value);
        ConfigText.Lines lines = new ConfigText.Lines(text);
        Walk walk = walk(ConfigText.root(text), at);
        if (!walk.complete(at)) {
            return insertMissing(text, lines, walk, at, scalar, false);
        }
        if (!(walk.last() instanceof ScalarNode target)) {
            throw new ConfigException(at + " holds a list or a mapping, not a single value; edit it by hand");
        }
        int start = lines.index(target.getStartMark());
        int end = lines.index(target.getEndMark());
        // An empty value ("model:") starts right after its colon.
        String replacement = start == end ? " " + scalar : scalar;
        return text.substring(0, start) + replacement + text.substring(end);
    }

    /** Removes the key, or the list item, at {@code at}, with every line it spans. */
    public static String remove(String text, At at) {
        if (at.steps().isEmpty()) {
            throw new IllegalArgumentException("remove needs a key or an item");
        }
        ConfigText.Lines lines = new ConfigText.Lines(text);
        Walk walk = walk(ConfigText.root(text), at);
        if (!walk.complete(at)) {
            throw new ConfigException("no " + at + " in the config");
        }
        Node parent = walk.nodes.get(at.steps().size() - 1);
        refuseFlow(parent, at.describe(at.steps().size() - 1));
        Node target = walk.last();
        Node first = at.steps().getLast() instanceof Key ? walk.keys.getLast() : target;
        String before = lines.prefix(first.getStartMark()).strip();
        if (at.steps().getLast() instanceof Key ? !before.isEmpty() : !before.endsWith("-")) {
            throw new ConfigException("cannot remove " + at + ": it shares its line with other entries; edit it by hand");
        }
        int from = lines.startOfLine(first.getStartMark().getLine());
        int to = lines.startOfLineAfter(ConfigText.lastLine(target));
        return text.substring(0, from) + text.substring(to);
    }

    /** Appends {@code value} to the list of plain values at {@code at}; a missing or empty list is created. */
    public static String append(String text, At at, String value) {
        if (at.steps().isEmpty() || !(at.steps().getLast() instanceof Key)) {
            throw new IllegalArgumentException("append needs a list's key at the end, got " + at);
        }
        String scalar = scalar(value);
        ConfigText.Lines lines = new ConfigText.Lines(text);
        Walk walk = walk(ConfigText.root(text), at);
        if (!walk.complete(at)) {
            return insertMissing(text, lines, walk, at, scalar, true);
        }
        Node target = walk.last();
        if (target instanceof SequenceNode list && list.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !list.getValue().isEmpty()) {
            String prefix = lines.prefix(list.getValue().getFirst().getStartMark());
            int insertAt = lines.startOfLineAfter(ConfigText.lastLine(list));
            return new StringBuilder(text).insert(insertAt, prefix + scalar + lines.newline).toString();
        }
        if (isEmpty(target)) {
            Node key = walk.keys.getLast();
            String item = " ".repeat(key.getStartMark().getColumn() + 2) + "- " + scalar + lines.newline;
            return new StringBuilder(text).insert(lines.startOfLineAfter(key.getStartMark().getLine()), item).toString();
        }
        refuseFlow(target, at.toString());
        throw new ConfigException(at + " is not a list; edit it by hand");
    }

    /** The value as YAML: plain when that reads the same, single-quoted otherwise. */
    static String scalar(String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new ConfigException("a value must fit on one line");
        }
        return RESERVED.contains(value.toLowerCase(Locale.ROOT)) ? ConfigText.quoted(value) : ConfigText.yaml(value);
    }

    /** Adds the keys of {@code at} that the walk did not find, below the deepest mapping it found. */
    private static String insertMissing(String text, ConfigText.Lines lines, Walk walk, At at, String scalar, boolean list) {
        int found = walk.nodes.size() - 1;
        List<String> names = new ArrayList<>();
        for (int i = found; i < at.steps().size(); i++) {
            if (!(at.steps().get(i) instanceof Key key)) {
                throw new ConfigException("no " + at.describe(i + 1) + " in the config");
            }
            names.add(key.name());
        }
        Node parent = walk.last();
        int indent;
        int insertAt;
        if (parent instanceof MappingNode map && map.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !map.getValue().isEmpty()) {
            indent = map.getValue().getFirst().getKeyNode().getStartMark().getColumn();
            insertAt = lines.startOfLineAfter(ConfigText.lastLine(map));
        } else if (isEmpty(parent) && found > 0) {
            Node key = walk.keys.getLast();
            indent = key.getStartMark().getColumn() + 2;
            insertAt = lines.startOfLineAfter(key.getStartMark().getLine());
        } else {
            refuseFlow(parent, at.describe(found));
            throw new ConfigException(at.describe(found) + " is not a mapping; edit it by hand");
        }
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String pad = " ".repeat(indent + 2 * i);
            boolean leaf = i == names.size() - 1;
            block.append(pad).append(names.get(i)).append(':');
            if (leaf && !list) {
                block.append(' ').append(scalar);
            }
            block.append(lines.newline);
            if (leaf && list) {
                block.append(pad).append("  - ").append(scalar).append(lines.newline);
            }
        }
        return new StringBuilder(text).insert(insertAt, block).toString();
    }

    private static void refuseFlow(Node node, String where) {
        if (node instanceof CollectionNode<?> collection && collection.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            throw new ConfigException((where.isEmpty() ? "the config" : where) + " is written in flow style ({...} or [...]); "
                    + "rewrite it as a block, one entry per line, to change it here");
        }
    }

    private static boolean isEmpty(Node node) {
        return node instanceof ScalarNode scalar && scalar.getValue().isEmpty()
                && scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN;
    }

    /** The nodes found along a path: {@code nodes.get(i)} after i steps, {@code keys.get(i - 1)} the key node of step i, or null. */
    private static final class Walk {

        final List<Node> nodes = new ArrayList<>();
        final List<Node> keys = new ArrayList<>();

        boolean complete(At at) {
            return nodes.size() == at.steps().size() + 1;
        }

        Node last() {
            return nodes.getLast();
        }
    }

    private static Walk walk(MappingNode root, At at) {
        Walk walk = new Walk();
        walk.nodes.add(root);
        Node current = root;
        for (Step step : at.steps()) {
            Node next = null;
            Node key = null;
            switch (step) {
                case Key wanted -> {
                    if (current instanceof MappingNode map) {
                        for (NodeTuple tuple : map.getValue()) {
                            if (tuple.getKeyNode() instanceof ScalarNode name && name.getValue().equals(wanted.name())) {
                                next = tuple.getValueNode();
                                key = name;
                                break;
                            }
                        }
                    }
                }
                case Item wanted -> {
                    if (current instanceof SequenceNode list) {
                        for (Node item : list.getValue()) {
                            if (item instanceof MappingNode map && wanted.value().equals(field(map, wanted.field()))) {
                                next = item;
                                break;
                            }
                        }
                    }
                }
                case Value wanted -> {
                    if (current instanceof SequenceNode list) {
                        for (Node item : list.getValue()) {
                            if (item instanceof ScalarNode scalar && scalar.getValue().equals(wanted.value())) {
                                next = item;
                                break;
                            }
                        }
                    }
                }
            }
            if (next == null) {
                return walk;
            }
            walk.nodes.add(next);
            walk.keys.add(key);
            current = next;
        }
        return walk;
    }

    private static String field(MappingNode map, String field) {
        for (NodeTuple tuple : map.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode name && name.getValue().equals(field)
                    && tuple.getValueNode() instanceof ScalarNode value) {
                return value.getValue();
            }
        }
        return null;
    }
}
