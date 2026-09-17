package dispatch.cli;

/** What the setup commands show the person at the terminal, and ask them. */
public interface Terminal {

    void say(String line);

    /** The answer, or {@code defaultValue} when the answer is blank; null when input has ended. */
    String ask(String question, String defaultValue);

    /** An answer that is not shown while typed, for secrets; null when input has ended. */
    String askSecret(String question);
}
