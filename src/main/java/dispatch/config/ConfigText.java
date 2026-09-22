package dispatch.config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Adds to a config file's text without reformatting it: comments, order, spacing and line endings stay as written. The
 * positions of the new lines come from where SnakeYAML found the existing nodes; re-serializing the tree would lose the
 * comments' places.
 */
public final class ConfigText {

    private record Insert(int index, String text) {
    }

    private ConfigText() {
    }

    /**
     * @param name         the project's name as it should appear in YAML
     * @param projectLines the project's lines without indentation, "name: ..." first
     */
    public static String addProject(String text, String group, String name, List<String> projectLines) {
        MappingNode top = root(text);
        Lines lines = new Lines(text);
        List<Insert> inserts = new ArrayList<>(List.of(
                intoGroup(lines, groupProjects(top, group), name),
                intoProjects(lines, top, projectLines)));
        inserts.sort(Comparator.comparingInt(Insert::index).reversed());
        StringBuilder edited = new StringBuilder(text);
        inserts.forEach(insert -> edited.insert(insert.index(), insert.text()));
        return edited.toString();
    }

    /** Adds a member to a group's members, block or flow style; the name is always quoted. */
    public static String addMember(String text, String group, long id, String name) {
        MappingNode top = root(text);
        Lines lines = new Lines(text);
        SequenceNode members = groupList(top, group, "members");
        if (members.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            int closingBracket = lines.index(members.getEndMark()) - 1;
            String entry = "{ id: " + id + ", name: " + quoted(name) + " }";
            return new StringBuilder(text).insert(closingBracket, members.getValue().isEmpty() ? entry : ", " + entry).toString();
        }
        if (members.getValue().isEmpty()) {
            throw new ConfigException("group '" + group + "' has no members to add to; add the member by hand");
        }
        String prefix = lines.prefix(members.getValue().getFirst().getStartMark());
        String entry = prefix + "id: " + id + lines.newline + " ".repeat(prefix.length()) + "name: " + quoted(name) + lines.newline;
        return new StringBuilder(text).insert(lines.startOfLineAfter(lastLine(members.getValue().getLast())), entry).toString();
    }

    /** Characters that YAML reads as an indicator when they lead a plain scalar, e.g. '-' as a sequence entry. */
    private static final String LEADING_INDICATORS = "-?:,[]{}#&*!|>'\"%@`";

    /** A YAML scalar for {@code value}: plain when that reads the same, single-quoted otherwise. */
    public static String yaml(String value) {
        if (!value.isEmpty() && LEADING_INDICATORS.indexOf(value.charAt(0)) >= 0) {
            return quoted(value);
        }
        return value.matches("[A-Za-z0-9._/-]+") ? value : quoted(value);
    }

    public static String quoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static MappingNode root(String text) {
        try {
            if (new Yaml().compose(new StringReader(text)) instanceof MappingNode top) {
                return top;
            }
        } catch (YAMLException e) {
            throw new ConfigException("the config is not valid YAML: " + e.getMessage());
        }
        throw new ConfigException("the config is not a YAML mapping");
    }

    private static SequenceNode groupProjects(MappingNode top, String group) {
        return groupList(top, group, "projects");
    }

    private static SequenceNode groupList(MappingNode top, String group, String list) {
        if (value(top, "telegram") instanceof MappingNode telegram && value(telegram, "groups") instanceof SequenceNode groups) {
            for (Node item : groups.getValue()) {
                if (item instanceof MappingNode candidate && value(candidate, "name") instanceof ScalarNode found
                        && found.getValue().equals(group)) {
                    if (value(candidate, list) instanceof SequenceNode items) {
                        return items;
                    }
                    throw new ConfigException("group '" + group + "' has no " + list + " list to add to; add it by hand");
                }
            }
        }
        throw new ConfigException("no group named '" + group + "' in the config");
    }

    private static Insert intoGroup(Lines lines, SequenceNode projects, String name) {
        if (projects.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            int closingBracket = lines.index(projects.getEndMark()) - 1;
            return new Insert(closingBracket, projects.getValue().isEmpty() ? name : ", " + name);
        }
        Node last = projects.getValue().getLast();
        return new Insert(lines.startOfLineAfter(lastLine(last)), lines.prefix(last.getStartMark()) + name + lines.newline);
    }

    private static Insert intoProjects(Lines lines, MappingNode top, List<String> projectLines) {
        if (!(value(top, "projects") instanceof SequenceNode projects) || projects.getFlowStyle() == DumperOptions.FlowStyle.FLOW
                || projects.getValue().isEmpty()) {
            throw new ConfigException("the config's projects are not a list of blocks; add the project by hand");
        }
        String prefix = lines.prefix(projects.getValue().getFirst().getStartMark());
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < projectLines.size(); i++) {
            block.append(i == 0 ? prefix : " ".repeat(prefix.length())).append(projectLines.get(i)).append(lines.newline);
        }
        int lastItemLine = projects.getValue().getLast().getStartMark().getLine();
        return new Insert(afterProjects(lines, top, lastItemLine), block.toString());
    }

    /**
     * After the last project's lines, including comments indented under it. Before the next top-level key, and before the
     * blank lines and unindented comments that introduce that key.
     */
    private static int afterProjects(Lines lines, MappingNode top, int lastItemLine) {
        List<NodeTuple> tuples = top.getValue();
        int at = tuples.indexOf(tuples.stream().filter(tuple -> key(tuple).equals("projects")).findFirst().orElseThrow());
        int line;
        if (at == tuples.size() - 1) {
            line = lines.count();
            while (line - 1 > lastItemLine && lines.text(line - 1).isBlank()) {
                line--;
            }
        } else {
            line = tuples.get(at + 1).getKeyNode().getStartMark().getLine();
            while (line - 1 > lastItemLine && (lines.text(line - 1).isBlank() || lines.text(line - 1).startsWith("#"))) {
                line--;
            }
        }
        return lines.startOfLine(line);
    }

    /**
     * The line a node's text ends on. A block collection's end mark is where the next token starts, often the next line, so
     * its last value decides.
     */
    static int lastLine(Node node) {
        if (node instanceof MappingNode map && map.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !map.getValue().isEmpty()) {
            return lastLine(map.getValue().getLast().getValueNode());
        }
        if (node instanceof SequenceNode list && list.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !list.getValue().isEmpty()) {
            return lastLine(list.getValue().getLast());
        }
        return node.getEndMark().getLine();
    }

    private static Node value(MappingNode map, String key) {
        return map.getValue().stream().filter(tuple -> key(tuple).equals(key)).map(NodeTuple::getValueNode).findFirst().orElse(null);
    }

    private static String key(NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : "";
    }

    /** The text's lines and their start offsets. SnakeYAML counts columns in code points, Java strings in chars. */
    static final class Lines {

        final String newline;
        private final String text;
        private final List<Integer> starts = new ArrayList<>(List.of(0));

        Lines(String text) {
            if (text.indexOf('\u0085') >= 0 || text.indexOf(' ') >= 0 || text.indexOf(' ') >= 0) {
                throw new ConfigException("the config contains a Unicode line separator; remove it and try again");
            }
            this.text = text;
            this.newline = text.contains("\r\n") ? "\r\n" : "\n";
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                    starts.add(i + 1);
                }
            }
        }

        int count() {
            return starts.size();
        }

        String text(int line) {
            int end = line + 1 < starts.size() ? starts.get(line + 1) : text.length();
            return text.substring(starts.get(line), end).stripTrailing();
        }

        int startOfLine(int line) {
            return line < starts.size() ? starts.get(line) : endWithNewline();
        }

        int startOfLineAfter(int line) {
            return startOfLine(line + 1);
        }

        int index(Mark mark) {
            return text.offsetByCodePoints(starts.get(mark.getLine()), mark.getColumn());
        }

        /** What precedes a node on its line, e.g. "  - " for a sequence item. */
        String prefix(Mark mark) {
            return text.substring(starts.get(mark.getLine()), index(mark));
        }

        /** The end of the text; a last line without a newline would run into the inserted one, so this is not supported. */
        private int endWithNewline() {
            if (!text.isEmpty() && !text.endsWith("\n")) {
                throw new ConfigException("the config's last line has no line break; add one and try again");
            }
            return text.length();
        }
    }
}
