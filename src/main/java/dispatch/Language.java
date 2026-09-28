package dispatch;

import java.util.Locale;

/** The two languages a page can ask for; the terminal and the log are English. */
public enum Language {
    EN, MN;

    /** Mongolian when the first range of an Accept-Language header names it (mn, mn-MN, mn-Cyrl-MN); English otherwise. */
    public static Language fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return EN;
        }
        String first = header.split(",", 2)[0];
        String primary = first.split("[-;]", 2)[0].strip().toLowerCase(Locale.ROOT);
        return primary.equals("mn") ? MN : EN;
    }
}
