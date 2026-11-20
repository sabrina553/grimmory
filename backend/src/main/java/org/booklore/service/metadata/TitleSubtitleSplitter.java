package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;

import java.util.Locale;

/**
 * Splits a provider title such as "Lamb: The Gospel According to Biff" into title and subtitle
 * when the provider returned both as one string. Applied once to every provider result, so
 * parsers don't each need their own copy of the rule.
 */
public final class TitleSubtitleSplitter {

    private static final String SEPARATOR = ": ";

    private TitleSubtitleSplitter() {
    }

    /**
     * Splits {@code metadata}'s title in place if it is safe to do so.
     *
     * @param metadata     a provider result; may be null
     * @param libraryTitle the title the book already has in the library, used to tell a real
     *                     subtitle apart from a series prefix; may be null
     * @return the same instance, for use in stream maps
     */
    public static BookMetadata split(BookMetadata metadata, String libraryTitle) {
        if (metadata == null || metadata.getTitle() == null) {
            return metadata;
        }
        if (metadata.getSubtitle() != null && !metadata.getSubtitle().isBlank()) {
            return metadata;
        }

        String fullTitle = metadata.getTitle();
        int separatorIndex = fullTitle.indexOf(SEPARATOR);
        if (separatorIndex <= 0) {
            return metadata;
        }

        String mainTitle = fullTitle.substring(0, separatorIndex).trim();
        String subtitle = fullTitle.substring(separatorIndex + SEPARATOR.length()).trim();
        if (mainTitle.isEmpty() || subtitle.isEmpty()) {
            return metadata;
        }

        if (libraryTitle != null && !libraryTitle.isBlank()) {
            String library = normalise(libraryTitle);
            // The library already uses the combined string, or the part after the colon is the
            // book's own title (so the prefix is a series name): either way, leave it alone.
            if (library.equals(normalise(fullTitle)) || library.equals(normalise(subtitle))) {
                return metadata;
            }
        }

        metadata.setTitle(mainTitle);
        metadata.setSubtitle(subtitle);
        return metadata;
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
