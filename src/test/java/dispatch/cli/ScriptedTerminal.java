package dispatch.cli;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A terminal that answers from a script and records everything shown, including which questions hid their answer. */
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
    public String ask(String question, String defaultValue) {
        shown.add("? " + question + (defaultValue == null ? "" : " [" + defaultValue + "]"));
        String answer = answers.poll();
        if (answer == null) {
            return null;
        }
        return answer.isBlank() && defaultValue != null ? defaultValue : answer;
    }

    @Override
    public String askSecret(String question) {
        shown.add("? (hidden) " + question);
        return answers.poll();
    }

    String output() {
        return String.join("\n", shown);
    }
}
