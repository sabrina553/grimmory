package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSubtitleSplitterTest {

    private static BookMetadata provider(String title, String subtitle) {
        return BookMetadata.builder().title(title).subtitle(subtitle).build();
    }

    @Test
    void splitsWhenLibraryTitleMatchesPrefix() {
        BookMetadata result = TitleSubtitleSplitter.split(
                provider("Lamb: The Gospel According to Biff", null), "Lamb");

        assertThat(result.getTitle()).isEqualTo("Lamb");
        assertThat(result.getSubtitle()).isEqualTo("The Gospel According to Biff");
    }

    @Test
    void splitsOnFirstSeparatorWhenNoLibraryTitle() {
        BookMetadata result = TitleSubtitleSplitter.split(
                provider("Dune: Book One: Special Edition", null), null);

        assertThat(result.getTitle()).isEqualTo("Dune");
        assertThat(result.getSubtitle()).isEqualTo("Book One: Special Edition");
    }

    @Test
    void keepsExistingSubtitle() {
        BookMetadata result = TitleSubtitleSplitter.split(
                provider("Lamb: The Gospel", "Already Set"), "Lamb");

        assertThat(result.getTitle()).isEqualTo("Lamb: The Gospel");
        assertThat(result.getSubtitle()).isEqualTo("Already Set");
    }

    @Test
    void keepsTitleWithoutSeparator() {
        BookMetadata result = TitleSubtitleSplitter.split(provider("Re:Zero", null), null);

        assertThat(result.getTitle()).isEqualTo("Re:Zero");
        assertThat(result.getSubtitle()).isNull();
    }

    @Test
    void keepsSeriesPrefixWhenLibraryTitleMatchesSuffix() {
        BookMetadata result = TitleSubtitleSplitter.split(
                provider("Star Wars: Heir to the Empire", null), "Heir to the Empire");

        assertThat(result.getTitle()).isEqualTo("Star Wars: Heir to the Empire");
        assertThat(result.getSubtitle()).isNull();
    }

    @Test
    void keepsCombinedTitleWhenLibraryUsesIt() {
        BookMetadata result = TitleSubtitleSplitter.split(
                provider("Star Wars: Heir to the Empire", null), "star wars - heir to the empire");

        assertThat(result.getTitle()).isEqualTo("Star Wars: Heir to the Empire");
        assertThat(result.getSubtitle()).isNull();
    }

    @Test
    void treatsBlankSubtitleAsMissing() {
        BookMetadata result = TitleSubtitleSplitter.split(provider("Lamb: The Gospel", " "), null);

        assertThat(result.getSubtitle()).isEqualTo("The Gospel");
    }

    @Test
    void handlesNulls() {
        assertThat(TitleSubtitleSplitter.split(null, "x")).isNull();
        assertThat(TitleSubtitleSplitter.split(provider(null, null), "x").getTitle()).isNull();
    }
}
