package dispatch.cli;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * A terminal that answers from a script and records what was shown. An answer for {@link #choose} is an option's label, for
 * {@link #confirm} "y" or "n"; a blank answer takes the default. When the script runs out, input has ended.
 */
final class ScriptedTerminal implements Terminal {

    final List<String> shown = new ArrayList<>();
    private final Deque<String> answers;

    ScriptedTerminal(String... answers) {
        this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public void say(String line) {
        shown.add(line);
    }

    @Override
    public void step(String title) {
        shown.add("STEP " + title);
    }

    @Override
    public void ok(String line) {
        shown.add("OK   " + line);
    }

    @Override
    public void warn(String line) {
        shown.add("WARN " + line);
    }

    @Override
    public void fail(String line) {
        shown.add("FAIL " + line);
    }

    @Override
    public String ask(String question, String defaultValue) {
        shown.add("? " + question + (defaultValue == null ? "" : " [" + defaultValue + "]"));
        String answer = next();
        return answer.isBlank() && defaultValue != null ? defaultValue : answer;
    }

    @Override
    public String askSecret(String question) {
        shown.add("? (hidden) " + question);
        return next();
    }

    @Override
    public <T> T choose(String question, List<Option<T>> options, int defaultIndex) {
        shown.add("? (choose) " + question + ": " + String.join(" | ", options.stream().map(Option::label).toList()));
        String answer = next();
        if (answer.isBlank()) {
            return options.get(defaultIndex).value();
        }
        return options.stream().filter(option -> option.label().equals(answer)).findFirst()
                .orElseThrow(() -> new AssertionError("no option '" + answer + "' for " + question)).value();
    }

    @Override
    public boolean confirm(String question, boolean defaultValue) {
        shown.add("? (confirm) " + question);
        String answer = next();
        return answer.isBlank() ? defaultValue : answer.startsWith("y");
    }

    @Override
    public <T> T during(String message, Supplier<T> work) {
        shown.add("... " + message);
        return work.get();
    }

    String output() {
        return String.join("\n", shown);
    }

    private String next() {
        String answer = answers.poll();
        if (answer == null) {
            throw new CliException("cancelled");
        }
        return answer;
    }
}
