package dispatch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Words for a person, in their language: a key into texts_en.properties and texts_mn.properties with its arguments,
 * words that read the same in every language (a name, or what another program wrote), or parts joined. A page reads the
 * language its request asked for; the terminal and the log read English, which is also what toString gives. A key is
 * always a literal where it is named, never built, so a test can check that the bundles have it.
 */
public sealed interface Text {

    String render(Language language);

    default String english() {
        return render(Language.EN);
    }

    /** @param args strings, numbers or other texts; a text renders in the same language as the whole */
    static Text of(String key, Object... args) {
        return new Keyed(key, Collections.unmodifiableList(Arrays.asList(args.clone())));
    }

    static Text raw(String words) {
        return new Raw(words);
    }

    static Text joined(String separator, List<Text> parts) {
        return new Joined(separator, List.copyOf(parts));
    }

    record Keyed(String key, List<Object> args) implements Text {
        @Override
        public String render(Language language) {
            return Texts.format(language, key, args);
        }

        @Override
        public String toString() {
            return english();
        }
    }

    record Raw(String words) implements Text {
        @Override
        public String render(Language language) {
            return words;
        }

        @Override
        public String toString() {
            return words;
        }
    }

    record Joined(String separator, List<Text> parts) implements Text {
        @Override
        public String render(Language language) {
            return parts.stream().map(part -> part.render(language)).collect(Collectors.joining(separator));
        }

        @Override
        public String toString() {
            return english();
        }
    }

    /** Writes a text as its words, in the language the writer was given (Json.write), English without one. */
    final class Serializer extends StdSerializer<Text> {

        public Serializer() {
            super(Text.class);
        }

        @Override
        public void serialize(Text text, JsonGenerator out, SerializerProvider provider) throws IOException {
            Object language = provider.getAttribute(Language.class);
            out.writeString(text.render(language instanceof Language chosen ? chosen : Language.EN));
        }
    }
}
