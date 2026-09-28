package dispatch;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The bundles behind {@link Text}: texts_en.properties and texts_mn.properties. */
final class Texts {

    private static final Map<Language, ResourceBundle> BUNDLES = Map.of(
            Language.EN, bundle(Locale.of("en")),
            Language.MN, bundle(Locale.of("mn")));
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private Texts() {
    }

    static String format(Language language, String key, List<Object> args) {
        Object[] words = new Object[args.size()];
        for (int i = 0; i < words.length; i++) {
            Object arg = args.get(i);
            // Strings, so MessageFormat never groups a number's digits; a text in the same language as the whole.
            words[i] = arg instanceof Text text ? text.render(language) : String.valueOf(arg);
        }
        return new MessageFormat(pattern(language, key), Locale.ROOT).format(words);
    }

    private static String pattern(Language language, String key) {
        ResourceBundle bundle = BUNDLES.get(language);
        if (bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        if (language == Language.EN) {
            throw new IllegalStateException("no text " + key + " in texts_en.properties");
        }
        if (MISSING.add(language + "/" + key)) {
            Log.warn("text.missing", "key", key, "language", language);
        }
        return pattern(Language.EN, key);
    }

    private static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle("texts", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
