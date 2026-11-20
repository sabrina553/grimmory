package org.booklore.service.metadata.parser;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.WordUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.hardcover.GraphQLResponse;
import org.booklore.service.metadata.parser.hardcover.HardcoverBookSearchService;
import org.booklore.service.metadata.parser.hardcover.HardcoverMoodFilter;
import org.booklore.util.BookUtils;
import org.booklore.util.LanguageNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class HardcoverParser implements BookParser {
    private static final String EXTERNAL_URL_TEMPLATE = "https://hardcover.app/books/{id}";

    // Below this a hit is treated as a different book, so the next provider gets a chance.
    static final double MIN_TITLE_SIMILARITY = 0.7;

    private final HardcoverBookSearchService hardcoverBookSearchService;
    private final AppSettingService appSettingService;

    private Optional<MetadataProviderSettings.Hardcover> getSettings() {
        var appSettings = appSettingService.getAppSettings();

        if (
                appSettings == null ||
                appSettings.getMetadataProviderSettings() == null
        ) {
            return Optional.empty();
        }

        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getHardcover());
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = getSettings().map(MetadataProviderSettings.Hardcover::isEnabled).orElse(false);
        String apiKey = getSettings().map(MetadataProviderSettings.Hardcover::getApiKey).orElse(null);
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {

        //Try search by Isbn
        List<BookMetadata> metadata = searchByIsbn(fetchMetadataRequest);
        if (!metadata.isEmpty()) {
            return metadata;
        }

        // Else Search by Title/Author
        List<GraphQLResponse.Document> docs = searchByTitle(fetchMetadataRequest);

        // Then Search by hardcover id for any returned search results
        List<GraphQLResponse.BookWithEditions> results = searchById(docs, fetchMetadataRequest);

        // further filter editions of returned books
        results = filterEditions(results, book);
        return processBooks(results);
    }

    private List<BookMetadata> searchByIsbn(FetchMetadataRequest request) {

        String cleanedIsbn = ParserUtils.cleanIsbn(request.getIsbn());
        if (cleanedIsbn == null || cleanedIsbn.isBlank()) {
            return Collections.emptyList();
        }
        List<String> isbnCleaned = List.of(cleanedIsbn);

        log.info("Hardcover: Fetching metadata using ISBN {}", isbnCleaned.get(0));
        List<GraphQLResponse.BookWithEditions> hits = hardcoverBookSearchService.searchBookByIsbn(isbnCleaned);
        return processBooks(hits);
    }

    private List<BookMetadata> processBooks(List<GraphQLResponse.BookWithEditions> books) {
        if (books == null || books.isEmpty()) {
            return Collections.emptyList();
        }

        List<BookMetadata> results = new ArrayList<>();
        for (GraphQLResponse.BookWithEditions book : books) {
            if (book.getEditions() == null || book.getEditions().isEmpty()) {
                continue;
            }
            results.add(mapBookToMetadata(book, book.getEditions().getFirst()));
        }
        return results;
    }

    private List<GraphQLResponse.Document> searchByTitle(FetchMetadataRequest fetchMetadataRequest) {
        List<GraphQLResponse.Document> results = new ArrayList<>();
        String title = fetchMetadataRequest.getTitle();

        if (title == null || title.isBlank()) {
            log.warn("Hardcover: No title provided for search");
            return Collections.emptyList();
        }
        String author = fetchMetadataRequest.getAuthor();

        // 1. Try Title + Author
        if (author != null && !author.isBlank()) {
            author = formatRequestAuthor(author);
            log.info("Hardcover: Searching with title+author: '{} {}'", title, author);
            List<GraphQLResponse.Hit> hits = hardcoverBookSearchService.searchBooks(title, author);
            results = filterSearch(hits, fetchMetadataRequest);
        }

        // 2. If no valid results found (or no author provided), Try Title only
        if (results.isEmpty()) {
            log.info("Hardcover: Searching with title only: '{}'", title);
            List<GraphQLResponse.Hit> hits = hardcoverBookSearchService.searchBooks(title);
            results = filterSearch(hits, fetchMetadataRequest);
        }

        // 3. Return empty if no results
        if (results.isEmpty()) {
            log.info("No results searching: {} {}", title, author);
            return Collections.emptyList();
        }
        return results;
    }

    private String formatRequestAuthor(String input) {
        if (input == null) {
            return "";
        }
        String[] authorArray = input.split(", ");
        input = authorArray.length > 0 ? authorArray[0] : "";
        return input;
    }

    private List<GraphQLResponse.Document> filterSearch(List<GraphQLResponse.Hit> hits, FetchMetadataRequest request) {
        if (hits == null || hits.isEmpty()) {
            log.info("Hardcover: No results found for title '{}'", request.getTitle());
            return Collections.emptyList();
        }

        String searchTitle = request.getTitle() != null ? request.getTitle() : "";
        String searchAuthor = request.getAuthor() != null ? formatRequestAuthor(request.getAuthor()) : "";

        log.debug("Filtering by title: '{}', author: '{}'", searchTitle, searchAuthor);

        Function<GraphQLResponse.Document, ScoredDocument> titleScorer = mapTitleSimilarity(searchTitle);
        Function<GraphQLResponse.Document, ScoredDocument> authorScorer = mapAuthorLevenshtien(searchAuthor);

        // Title similarity is a hard floor: a search always returns something, so without it the
        // best of a bad list gets applied and lower-priority providers never get a chance.
        return hits.stream()
                .map(GraphQLResponse.Hit::getDocument)
                .filter(Objects::nonNull)
                .map(doc -> new RankedDocument(
                        doc,
                        searchTitle.isBlank() ? 1.0 : titleScorer.apply(doc).score(),
                        searchAuthor.isBlank() ? 0.0 : authorScorer.apply(doc).score(),
                        doc.getUsersCount() == null ? 0 : doc.getUsersCount()))
                .filter(ranked -> ranked.titleSimilarity() >= MIN_TITLE_SIMILARITY)
                .filter(ranked -> searchAuthor.isBlank() || ranked.authorDistance() <= searchAuthor.length() * 0.8)
                .sorted(Comparator.comparingDouble(RankedDocument::titleSimilarity).reversed()
                        .thenComparingDouble(RankedDocument::authorDistance)
                        .thenComparing(Comparator.comparingInt(RankedDocument::usersCount).reversed()))
                .map(RankedDocument::document)
                .toList();
    }

    /**
     * Scores a document by how closely its title (or best alternative title) matches the search,
     * from 0 (unrelated) to 1 (identical after normalisation).
     */
    private Function<GraphQLResponse.Document, ScoredDocument> mapTitleSimilarity(String searchTitle) {
        String normalisedSearch = normaliseTitle(searchTitle);

        return (GraphQLResponse.Document doc) -> {
            List<String> candidates = new ArrayList<>();
            if (doc.getTitle() != null) {
                candidates.add(doc.getTitle());
            }
            if (doc.getAlternativeTitles() != null) {
                candidates.addAll(doc.getAlternativeTitles());
            }
            double best = candidates.stream()
                    .filter(Objects::nonNull)
                    .mapToDouble(candidate -> titleSimilarity(normalisedSearch, candidate))
                    .max()
                    .orElse(0.0);
            return new ScoredDocument(doc, best);
        };
    }

    static double titleSimilarity(String normalisedSearch, String candidateTitle) {
        if (normalisedSearch.isBlank()) {
            return 0.0;
        }
        String normalisedCandidate = normaliseTitle(candidateTitle);
        if (normalisedCandidate.isBlank()) {
            return 0.0;
        }
        int maxLength = Math.max(normalisedSearch.length(), normalisedCandidate.length());
        double editSimilarity = 1.0 - (double) LevenshteinDistance.getDefaultInstance()
                .apply(normalisedSearch, normalisedCandidate) / maxLength;

        // Multi-word searches also match when every word appears in the candidate, so
        // "Ready Player One" still finds "Summary of Ready Player One". Single words are
        // excluded because a short word like "it" appears in almost everything.
        List<String> searchWords = List.of(normalisedSearch.split(" "));
        Set<String> candidateWords = new HashSet<>(Arrays.asList(normalisedCandidate.split(" ")));
        boolean allWordsPresent = searchWords.size() > 1 && candidateWords.containsAll(searchWords);

        return allWordsPresent ? Math.max(editSimilarity, MIN_TITLE_SIMILARITY) : editSimilarity;
    }

    /**
     * Lowercases, drops any subtitle after ':', and collapses punctuation to single spaces,
     * so "The Hobbit: There and Back Again" and "the hobbit" compare as equal.
     */
    static String normaliseTitle(String title) {
        if (title == null) {
            return "";
        }
        String mainTitle = title.split(":", 2)[0];
        return mainTitle.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private Function<GraphQLResponse.Document, ScoredDocument> mapAuthorLevenshtien(String searchAuthor) {
        LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();
        String searchAuthorLowercase = searchAuthor.toLowerCase();

        return (GraphQLResponse.Document doc) -> {
            if (doc.getAuthorNames() == null || doc.getAuthorNames().isEmpty()) {
                return new ScoredDocument(doc, Double.MAX_VALUE);
            }

            List<String> normalizedAuthors = doc.getAuthorNames().stream()
                    .map(String::toLowerCase)
                    .toList();

            //Find the author in the list of authors with the closest name to the search term
            double minDistance = normalizedAuthors.stream()
                    .map(author -> levenshtein.apply(searchAuthorLowercase, author))
                    .min(Double::compare)
                    .orElse(Integer.MAX_VALUE);

            return new ScoredDocument(doc, minDistance);
        };
    }

    private List<GraphQLResponse.BookWithEditions> searchById(List<GraphQLResponse.Document> docs, FetchMetadataRequest request) {
        if (docs == null || docs.isEmpty()) {
            log.warn("No documents provided for search by ID.");
            return Collections.emptyList();
        }
        // Extract hcid values from the docs
        List<Integer> hcid = docs.stream()
                .map(doc -> {
                    try {
                        return Integer.parseInt(doc.getId());
                    }
                    catch (NumberFormatException e) {
                        log.warn("Invalid ID: {}", doc.getId(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        log.info("Searching by Hardcover ID for {}", request.getTitle());
        List<GraphQLResponse.BookWithEditions> results = hardcoverBookSearchService.searchBookByHcid(hcid);

        Map<Integer, GraphQLResponse.BookWithEditions> bookMap = results.stream()
                .collect(Collectors.toMap(
                    GraphQLResponse.BookWithEditions::getId,
                    Function.identity()
                ));

        // Return results in the same order as (hcid) recived
        return hcid.stream()
                .map(bookMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<GraphQLResponse.BookWithEditions> filterEditions(List<GraphQLResponse.BookWithEditions> results, Book book) {
        return results.stream()
                .map(result -> {
                    // filter by format
                    List<GraphQLResponse.Edition> filteredByFormat = filterEditionsByFormat(result.getEditions(), book);

                    // filter by language
                    List<GraphQLResponse.Edition> filteredByLanguage = filterEditionsByLanguage(filteredByFormat);

                    // remove editions with no isbn
                    List<GraphQLResponse.Edition> filteredByISBN = filterEditionsByISBN(filteredByLanguage);

                    result.setEditions(filteredByISBN);
                    return result;
                })
                .toList();
    }

    private List<GraphQLResponse.Edition> filterEditionsByFormat(List<GraphQLResponse.Edition> editions, Book book)
    {
        if (book.getPrimaryFile() == null){
            return editions;
        }

        boolean isAudiobook = book.getPrimaryFile().getBookType().equals(BookFileType.AUDIOBOOK);
        List<GraphQLResponse.Edition> audiobooks = new ArrayList<>();
        List<GraphQLResponse.Edition> hardcovers = new ArrayList<>();

        for (GraphQLResponse.Edition edition : editions) {
            if (edition.getReadingFormatId() == 2) {
                audiobooks.add(edition);
            } else {
                hardcovers.add(edition);
            }
        }

        return isAudiobook && !audiobooks.isEmpty()
            ? audiobooks
            : hardcovers;
    }

    private List<GraphQLResponse.Edition> filterEditionsByLanguage(List<GraphQLResponse.Edition> result){
        String localeLanguage = Locale.getDefault().getLanguage();

        if (result == null || result.isEmpty()) {
            return result;
        }

        List<GraphQLResponse.Edition> filteredEditions = result.stream()
                .filter(edition -> {
                    String languageCode = edition.getLanguage() != null
                            ? edition.getLanguage().getCode2()
                            : null;
                    return languageCode != null && languageCode.equals(localeLanguage);
                })
                .toList();

        return !filteredEditions.isEmpty()
            ? filteredEditions
            : result;
    }

    private List<GraphQLResponse.Edition> filterEditionsByISBN(List<GraphQLResponse.Edition> result){
        if (result == null || result.isEmpty()) {
            return result;
        }

        List<GraphQLResponse.Edition> filteredEditions = result.stream()
                .filter(edition -> edition.getIsbn10() != null || edition.getIsbn13() != null)
                .toList();

        return !filteredEditions.isEmpty()
            ?   filteredEditions
            :   result;
    }

    private BookMetadata mapBookToMetadata(GraphQLResponse.BookWithEditions book, GraphQLResponse.Edition edition) {
        var builder = BookMetadata.builder();

        if (edition.getSubtitle() != null && book.getTitle().contains(edition.getSubtitle())) {
            book.setTitle(book.getTitle().replace(": " + book.getSubtitle(), ""));
        }

        String externalUrl = UriComponentsBuilder.fromUriString(EXTERNAL_URL_TEMPLATE)
                .build(book.getSlug())
                .toString();

        builder.hardcoverId(book.getSlug())
                .title(book.getTitle())
                .subtitle(edition.getSubtitle())
                .pageCount(book.getPages())
                .description(book.getDescription())
                .hardcoverReviewCount(book.getReviewsCount())
                .thumbnailUrl(book.getImage() != null ? book.getImage().getUrl() : null)
                .provider(MetadataProvider.Hardcover)
                .externalUrl(externalUrl);

        mapBookId(builder, book);
        mapCachedContributors(builder, book);
        mapReleaseDate(builder, book);
        mapSeriesData(builder, book);
        mapRating(builder, book);

        GraphQLResponse.CachedTags cachedTags = book.getCachedTags();

        mapMoods(builder, cachedTags);
        mapCategories(builder, cachedTags);
        mapTags(builder, cachedTags);
        mapSeriesInfo(builder, book);

        mapIsbn(builder, edition);
        mapLanguage(builder, edition);
        mapPublisher(builder, edition);
        mapEditionReleaseDate(builder, edition);

        return builder.build();
    }

    private void mapBookId(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book) {
        if (book.getId() != null) {
            builder.hardcoverBookId(book.getId().toString());
        }
    }

    private void mapSeriesData(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book) {
        if (book.getFeaturedBookSeries() != null && book.getFeaturedBookSeries().getSeries() != null) {
            builder.seriesName(book.getFeaturedBookSeries().getSeries().getName());
            builder.seriesTotal(book.getFeaturedBookSeries().getSeries().getPrimaryBooksCount());

            if (book.getFeaturedBookSeries().getPosition() != null) {
                try {
                    builder.seriesNumber(Float.parseFloat(String.valueOf(book.getFeaturedBookSeries()
                            .getPosition())));
                } catch (NumberFormatException _) {
                    // Handle the case where the series number cannot be parsed as a float
                }
            }
        }
    }

    private void mapRating(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book) {
        if (book.getRating() != null) {
            builder.hardcoverRating(
                    BigDecimal.valueOf(book.getRating())
                            .setScale(2, RoundingMode.HALF_UP)
                            .doubleValue());
        }
    }

    private void mapReleaseDate(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book) {
        if (book.getReleaseDate() != null) {
            try {
                builder.publishedDate(LocalDate.parse(book.getReleaseDate()));
            } catch (Exception _) {
                log.debug("Could not parse release date: {}", book.getReleaseDate());
            }
        }
    }

    private void mapSeriesInfo(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book) {
        if (book.getFeaturedBookSeries() == null || book.getFeaturedBookSeries().getSeries() == null) {

                return;
        }
        builder.seriesName(book.getFeaturedBookSeries().getSeries().getName());
        builder.seriesTotal(book.getFeaturedBookSeries().getSeries().getPrimaryBooksCount());

        if (book.getFeaturedBookSeries().getPosition() != null) {
            try {
                builder.seriesNumber(Float.parseFloat(String.valueOf(book.getFeaturedBookSeries().getPosition())));
            } catch (NumberFormatException _) {
                // Ignore parsing error if the series position is not a valid number
            }
        }
    }

    private void mapMoods(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.CachedTags cachedTags) {
        if (cachedTags != null && cachedTags.getMood() != null && !cachedTags.getMood().isEmpty()) {
            Set<String> basicFilteredMoods = HardcoverMoodFilter.filterMoodsWithCounts(cachedTags.getMood());
            builder.moods(basicFilteredMoods.stream()
                    .map(WordUtils::capitalizeFully)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
    }

    private void mapCategories(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.CachedTags cachedTags) {
        if (cachedTags != null && cachedTags.getGenre() != null && !cachedTags.getGenre().isEmpty()) {
            Set<String> filteredGenres = HardcoverMoodFilter.filterGenresWithCounts(cachedTags.getGenre());
            builder.categories(filteredGenres.stream()
                    .map(WordUtils::capitalizeFully)
                    .collect(Collectors.toSet()));
        }
    }

    private void mapTags(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.CachedTags cachedTags) {
        if (cachedTags != null && cachedTags.getTag() != null && !cachedTags.getTag().isEmpty()) {
            Set<String> filteredTags = HardcoverMoodFilter.filterTagsWithCounts(cachedTags.getTag());
            builder.tags(filteredTags.stream()
                    .map(WordUtils::capitalizeFully)
                    .collect(Collectors.toSet()));
        }
    }

    private void mapCachedContributors(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.BookWithEditions book){
        if (book.getCachedContributors() != null) {
            builder.authors(book.getCachedContributors().stream()
                    .map(GraphQLResponse.Contributor::getAuthor)
                    .filter(Objects::nonNull)
                    .map(GraphQLResponse.Author::getName)
                    .filter(Objects::nonNull)
                    .toList());
        }
    }

    private void mapEditionReleaseDate(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.Edition edition) {
        if (edition.getReleaseDate() != null) {
            try {
                builder.publishedDate(LocalDate.parse(edition.getReleaseDate()));
            } catch (Exception _) {
                log.debug("Could not parse release date: {}", edition.getReleaseDate());
            }
        }
    }

    private void mapIsbn(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.Edition edition) {
        builder.isbn10(edition.getIsbn10());
        builder.isbn13(edition.getIsbn13());

        if (edition.getIsbn10() != null && edition.getIsbn13() == null) {
            builder.isbn13(BookUtils.isbn10To13(edition.getIsbn10()));
        } else if (edition.getIsbn13() != null && edition.getIsbn10() == null) {
            builder.isbn10(BookUtils.isbn13to10(edition.getIsbn13()));
        }
    }

    private void mapLanguage(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.Edition edition) {
        if (edition.getLanguage() != null && edition.getLanguage().getCode2() != null) {
            builder.language(LanguageNormalizer.normalize(edition.getLanguage().getCode2()));
        }
    }

    private void mapPublisher(BookMetadata.BookMetadataBuilder builder, GraphQLResponse.Edition edition) {
        if (edition.getPublisher() != null && edition.getPublisher().getName() != null) {
            builder.publisher(edition.getPublisher().getName());
        }
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        List<BookMetadata> bookMetadata = fetchMetadata(book, fetchMetadataRequest);
        return bookMetadata.isEmpty() ? null : bookMetadata.getFirst();
    }

    record RankedDocument(
            GraphQLResponse.Document document,
            double titleSimilarity,
            double authorDistance,
            int usersCount
    ) {}

    record ScoredDocument (
            GraphQLResponse.Document document,
            double score
    ) {}
}