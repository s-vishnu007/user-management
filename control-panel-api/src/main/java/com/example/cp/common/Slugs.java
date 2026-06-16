package com.example.cp.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Derives a URL-safe organization slug from a free-text name.
 *
 * <p>Produces the {@code [a-z0-9-]} form expected by {@code OrgService}'s slug pattern
 * ({@code ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$}). Callers are responsible for ensuring uniqueness
 * (e.g. appending a numeric/random suffix) — this only normalises the shape.
 */
public final class Slugs {

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");

    private Slugs() {
    }

    /**
     * Normalises {@code input} to a slug body: lowercased, non-alphanumerics collapsed to single
     * dashes, leading/trailing dashes stripped. May return an empty string (e.g. for input with no
     * alphanumerics); callers must handle padding to the minimum length.
     */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String s = input.toLowerCase(Locale.ROOT).trim();
        s = NON_SLUG.matcher(s).replaceAll("-");
        s = EDGE_DASHES.matcher(s).replaceAll("");
        return s;
    }
}
