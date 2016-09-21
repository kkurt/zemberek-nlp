package zemberek.morphology.parser.tr;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import zemberek.core.io.Strings;
import zemberek.core.logging.Log;
import zemberek.morphology.generator.SimpleGenerator;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.morphology.lexicon.SuffixProvider;
import zemberek.morphology.lexicon.graph.DynamicLexiconGraph;
import zemberek.morphology.lexicon.tr.TurkishDictionaryLoader;
import zemberek.morphology.lexicon.tr.TurkishSuffixes;
import zemberek.morphology.parser.MorphParse;
import zemberek.morphology.parser.MorphParser;
import zemberek.morphology.parser.WordParser;
import zemberek.morphology.structure.StemAndEnding;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Turkish Morphological Parser finds all possible parses for a Turkish word.
 */
public class TurkishWordParserGenerator extends BaseParser implements java.io.Serializable {

    private MorphParser parser;
    private SimpleGenerator generator;
    private RootLexicon lexicon;
    private DynamicLexiconGraph graph;
    private SuffixProvider suffixProvider;
    private UnidentifiedTokenParser unidentifiedTokenParser;

    private LoadingCache<String, List<MorphParse>> dynamicCache;
    private StaticMorphCache staticCache;

    public static class TurkishMorphParserBuilder {
        MorphParser _parser;
        SimpleGenerator _generator;
        SuffixProvider suffixProvider = new TurkishSuffixes();
        RootLexicon lexicon = new RootLexicon();

        public TurkishMorphParserBuilder addDefaultDictionaries() throws IOException {
            return addTextDictResources(TurkishDictionaryLoader.DEFAULT_DICTIONARY_RESOURCES.toArray(
                    new String[TurkishDictionaryLoader.DEFAULT_DICTIONARY_RESOURCES.size()]));
        }

        public TurkishMorphParserBuilder addTextDictFiles(File... dictionaryFiles) throws IOException {
            List<String> lines = new ArrayList<>();
            for (File file : dictionaryFiles) {
                lines.addAll(Files.readAllLines(file.toPath()));
            }
            lexicon.addAll(new TurkishDictionaryLoader(suffixProvider).load(lines));
            return this;
        }

        public TurkishMorphParserBuilder removeDictFiles(File... dictionaryFiles) throws IOException {
            for (File file : dictionaryFiles) {
                lexicon.removeAll(new TurkishDictionaryLoader(suffixProvider).load(file));
            }
            return this;
        }

        public TurkishMorphParserBuilder addTextDictResources(String... resources) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String resource : resources) {
                lines.addAll(Resources.readLines(Resources.getResource(resource), Charsets.UTF_8));
            }
            lexicon.addAll(new TurkishDictionaryLoader(suffixProvider).load(lines));
            return this;
        }

        public TurkishMorphParserBuilder removeItems(Iterable<String> dictionaryString) throws IOException {
            lexicon.removeAll(new TurkishDictionaryLoader(suffixProvider).load(dictionaryString));
            return this;
        }

        public TurkishMorphParserBuilder removeAllLemmas(Iterable<String> lemmas) throws IOException {
            lexicon.removeAllLemmas(lemmas);
            return this;
        }

        public TurkishWordParserGenerator build() throws IOException {
            Stopwatch sw = Stopwatch.createStarted();
            DynamicLexiconGraph graph = new DynamicLexiconGraph(suffixProvider);
            graph.addDictionaryItems(lexicon);
            _parser = new WordParser(graph);
            _generator = new SimpleGenerator(graph);
            Log.info("Parser ready: " + sw.elapsed(TimeUnit.MILLISECONDS) + "ms.");
            return new TurkishWordParserGenerator(_parser, _generator, lexicon, graph, suffixProvider);
        }
    }

    private void generateCaches() {
        this.dynamicCache = CacheBuilder.newBuilder()
                .maximumSize(50000)
                .concurrencyLevel(1)
                .initialCapacity(20000)
                .build(new MorphParseCacheLoader());
        try {
            List<String> words = Resources.readLines(Resources.getResource("tr/top-20K-words.txt"), Charsets.UTF_8);
            staticCache = new StaticMorphCache(parser, words);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class MorphParseCacheLoader extends CacheLoader<String, List<MorphParse>> implements java.io.Serializable {
        @Override
        public List<MorphParse> load(String word) throws Exception {
            String s = normalize(word); // TODO: this may cause problem for some foreign words.
            if (s.length() == 0)
                return Collections.emptyList();
            List<MorphParse> res = parser.parse(s);
            if (res.size() == 0) {
                res.addAll(quoteParseCheck(s));
            }
            if (res.size() == 0) {
                invalidateCache(s);
                res.addAll(unidentifiedTokenParser.parse(s));
            }
            if (res.size() == 0) {
                res.add(new MorphParse(DictionaryItem.UNKNOWN, s, Lists.newArrayList(MorphParse.InflectionalGroup.UNKNOWN)));
            }
            return res;
        }

        public List<MorphParse> quoteParseCheck(String word) {
            List<MorphParse> results = new ArrayList<>(2);

            if (word.contains("'")) {

                StemAndEnding se = new StemAndEnding(Strings.subStringUntilFirst(word, "'"), Strings.subStringAfterFirst(word, "'"));
                String stem = normalize(se.stem);

                String withoutQuote = word.replaceAll("'", "");

                List<MorphParse> noQuotesParses = parser.parse(withoutQuote);
                results.addAll(noQuotesParses.stream()
                        .filter(noQuotesParse -> noQuotesParse.getStems().contains(stem))
                        .collect(Collectors.toList()));
            }
            return results;
        }
    }

    private TurkishWordParserGenerator(
            MorphParser parser,
            SimpleGenerator generator,
            RootLexicon lexicon,
            DynamicLexiconGraph graph,
            SuffixProvider suffixProvider) {
        this.parser = parser;
        this.generator = generator;
        this.lexicon = lexicon;
        this.graph = graph;
        this.unidentifiedTokenParser = new UnidentifiedTokenParser(this);
        this.suffixProvider = suffixProvider;
        generateCaches();
    }

    public static TurkishWordParserGenerator createWithDefaults() throws IOException {
        return new TurkishMorphParserBuilder().addDefaultDictionaries().build();
    }

    public static TurkishMorphParserBuilder builder() {
        return new TurkishMorphParserBuilder();
    }

    public MorphParser getParser() {
        return parser;
    }

    /**
     * Normalizes the input word and parses it. If word cannot be parsed following occurs:
     * - if input is a number, system tries to parse it by creating a number DictionaryEntry.
     * - if input starts with a capital letter, or contains ['] adds a Dictionary entry as a proper noun.
     * - if above options does not generate a result, it generates an UNKNOWN dictionary entry and returns a parse with it.
     *
     * @param word input word.
     * @return MorphParse list.
     */
    public List<MorphParse> parse(String word) {
        List<MorphParse> res = staticCache.parse(word);
        if (res == null) {
            res = dynamicCache.getUnchecked(word);
        }
        return res;
    }

    public void invalidateAllCache() {
        dynamicCache.invalidateAll();
        staticCache.removeAll();
    }

    public void invalidateCache(String input) {
        dynamicCache.invalidate(input);
        staticCache.remove(input);
    }

    public SimpleGenerator getGenerator() {
        return generator;
    }

    public RootLexicon getLexicon() {
        return lexicon;
    }

    public DynamicLexiconGraph getGraph() {
        return graph;
    }

    /**
     * Adds one or more dictionary items. Adding new dictionary items invalidates all caches.
     */
    public void addDictionaryItems(DictionaryItem... item) {
        this.graph.addDictionaryItems(item);
        invalidateAllCache();
    }


    public SuffixProvider getSuffixProvider() {
        return suffixProvider;
    }

    static class StaticMorphCache {
        private final HashMap<String, List<MorphParse>> cache;
        private long hit = 0;
        private long miss = 0;

        public StaticMorphCache(MorphParser parser, List<String> wordList) throws IOException {
            cache = Maps.newHashMapWithExpectedSize(5000);
            for (String s : wordList) {
                cache.put(s, parser.parse(s));
            }
        }

        public void put(String key, List<MorphParse> parses) {
            this.cache.put(key, parses);
        }

        public void remove(String key) {
            this.cache.remove(key);
        }

        public void removeAll() {
            cache.clear();
        }

        public List<MorphParse> parse(String s) {
            List<MorphParse> result = cache.get(s);
            if (result != null) {
                hit++;
            } else {
                miss++;
            }
            return result;
        }

        @Override
        public String toString() {
            return "Hits: " + hit + " Miss: " + miss + " Hit ratio: %" + (hit / (double) (hit + miss) * 100);
        }
    }
}
