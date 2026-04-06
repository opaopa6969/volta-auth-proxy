package com.volta.authproxy;

import java.text.MessageFormat;
import java.util.*;

/**
 * i18n message resolver. Loads messages from properties files.
 * Thread-safe, immutable after construction.
 *
 * Usage:
 *   Messages msg = Messages.forLocale("ja");
 *   String text = msg.get("login.title");
 *   String formatted = msg.get("invite.invited_by", "Alice", "Acme Corp");
 */
public final class Messages {
    private static final String DEFAULT_LOCALE = "ja";
    private static final Map<String, Messages> CACHE = new HashMap<>();
    private static final ThreadLocal<Messages> CURRENT = new ThreadLocal<>();

    /** Set the Messages for the current request (call in before handler, clear in after). */
    public static void setCurrent(Messages msg) { CURRENT.set(msg); }

    /** Get Messages for the current request. Falls back to default locale. */
    public static Messages current() {
        Messages msg = CURRENT.get();
        return msg != null ? msg : forLocale(DEFAULT_LOCALE);
    }

    /** Clear ThreadLocal (call in after handler). */
    public static void clearCurrent() { CURRENT.remove(); }

    private final String locale;
    private final Properties props;

    private Messages(String locale, Properties props) {
        this.locale = locale;
        this.props = props;
    }

    public String get(String key) {
        return props.getProperty(key, key);
    }

    public String get(String key, Object... args) {
        String pattern = props.getProperty(key, key);
        if (args.length == 0) return pattern;
        return MessageFormat.format(pattern, args);
    }

    public String locale() { return locale; }

    /**
     * Get Messages for the given locale. Falls back to default (ja) if not found.
     */
    public static synchronized Messages forLocale(String locale) {
        if (locale == null || locale.isBlank()) locale = DEFAULT_LOCALE;
        String lang = locale.toLowerCase(Locale.ROOT).split("[_-]")[0];

        return CACHE.computeIfAbsent(lang, Messages::load);
    }

    /**
     * Resolve locale from: user preference > Accept-Language header > default.
     */
    public static Messages resolve(String userLocale, String acceptLanguage) {
        if (userLocale != null && !userLocale.isBlank()) {
            return forLocale(userLocale);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String lang = parseAcceptLanguage(acceptLanguage);
            return forLocale(lang);
        }
        return forLocale(DEFAULT_LOCALE);
    }

    /**
     * All supported locale codes.
     */
    public static List<String> supportedLocales() {
        return List.of("ja", "en");
    }

    private static Messages load(String lang) {
        Properties props = new Properties();
        String filename = "messages_" + lang + ".properties";
        String effectiveLang = lang;
        try (var is = Messages.class.getClassLoader().getResourceAsStream(filename)) {
            if (is != null) {
                props.load(is);
            } else {
                effectiveLang = DEFAULT_LOCALE;
                try (var fallback = Messages.class.getClassLoader()
                        .getResourceAsStream("messages_" + DEFAULT_LOCALE + ".properties")) {
                    if (fallback != null) props.load(fallback);
                }
            }
        } catch (Exception e) {
            // Silent fallback — keys are returned as-is
        }
        return new Messages(effectiveLang, props);
    }

    private static String parseAcceptLanguage(String header) {
        // Parse "ja,en-US;q=0.9,en;q=0.8" → "ja"
        String best = DEFAULT_LOCALE;
        double bestQ = -1;
        for (String part : header.split(",")) {
            String[] langQ = part.trim().split(";");
            String lang = langQ[0].trim().split("-")[0].toLowerCase(Locale.ROOT);
            double q = 1.0;
            if (langQ.length > 1) {
                try {
                    q = Double.parseDouble(langQ[1].trim().replace("q=", ""));
                } catch (NumberFormatException ignored) {}
            }
            if (q > bestQ && supportedLocales().contains(lang)) {
                best = lang;
                bestQ = q;
            }
        }
        return best;
    }
}
