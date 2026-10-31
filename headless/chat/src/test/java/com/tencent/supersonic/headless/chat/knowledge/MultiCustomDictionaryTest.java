package com.tencent.supersonic.headless.chat.knowledge;

import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.dictionary.CoreDictionary;
import com.hankcs.hanlp.seg.common.Term;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MultiCustomDictionary} dictionary loading, exercised against a real HanLP
 * dictionary file on the test classpath (see
 * {@code src/test/resources/data/dictionary/custom/TestDict.txt}).
 *
 * <p>
 * These tests drive the static {@link MultiCustomDictionary#load} entry point directly, so they do
 * not require the HanLP core/custom dictionary data files to be present on this module's test
 * classpath nor a Spring context. Lowercase words are used in the fixture so that the
 * {@code isLetters} branch (which would otherwise call
 * {@code KnowledgeBaseService.addDimValueAlias} and pull in Spring-managed state) is not taken.
 */
class MultiCustomDictionaryTest {

    private static final String DICT_RESOURCE = "/data/dictionary/custom/TestDict.txt";

    private String dictPath;

    @BeforeEach
    void setUp() throws Exception {
        URL url = getClass().getResource(DICT_RESOURCE);
        assertNotNull(url, "test dictionary not found on classpath: " + DICT_RESOURCE);
        dictPath = new File(url.toURI()).getAbsolutePath();

        // reset shared static state so each test is independent
        MultiCustomDictionary.NATURE_TO_VALUES.clear();
        SearchService.clear();
    }

    @Test
    void load_shouldParseWordsAndNaturesIntoMap() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        boolean success =
                MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false);

        assertTrue(success);
        // tom, alice, lucy, sunglass — "tom" appears on two lines but collapses to one entry
        assertEquals(4, map.size());
        assertTrue(map.containsKey("tom"));
        assertTrue(map.containsKey("alice"));
        assertTrue(map.containsKey("lucy"));
        assertTrue(map.containsKey("sunglass"));
    }

    @Test
    void load_shouldParseMultipleNaturesAndAccumulateTotalFrequency() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        CoreDictionary.Attribute alice = map.get("alice");
        assertNotNull(alice);
        // line: "alice _1_2 6 _2_3 9" -> 2 natures, totalFrequency = 6 + 9 = 15
        assertEquals(2, alice.nature.length);
        assertEquals(15, alice.totalFrequency);

        List<String> aliceNatures = java.util.Arrays.stream(alice.nature).map(Nature::toString)
                .collect(Collectors.toList());
        assertTrue(aliceNatures.contains("_1_2"));
        assertTrue(aliceNatures.contains("_2_3"));
    }

    @Test
    void load_shouldMergeDuplicateWordsWhenRemoveDuplicatesEnabled() {
        // "tom" is declared twice (_1_2 5 and _3_4 8); with removeDuplicates=true (the default)
        // the two natures must be merged into a single attribute
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        CoreDictionary.Attribute tom = map.get("tom");
        assertNotNull(tom);
        assertEquals(2, tom.nature.length);

        List<String> tomNatures = java.util.Arrays.stream(tom.nature).map(Nature::toString)
                .collect(Collectors.toList());
        assertTrue(tomNatures.contains("_1_2"));
        assertTrue(tomNatures.contains("_3_4"));
        // DictionaryAttributeUtil sorts merged natures by frequency desc -> highest (8) first
        assertEquals(8, tom.frequency[0]);
        assertEquals("_3_4", tom.nature[0].toString());
        // totalFrequency is the highest frequency among the merged natures
        assertEquals(8, tom.totalFrequency);
    }

    @Test
    void load_shouldPopulateNatureToValuesIndex() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        // every distinct nature present in the dict should have a value queue
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_1_2"));
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_2_3"));
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_3_4"));
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_term"));

        PriorityBlockingQueue<Term> queue = MultiCustomDictionary.NATURE_TO_VALUES.get("_1_2");
        assertNotNull(queue);
        // tom, alice, lucy all carry nature _1_2 -> 3 distinct words indexed
        assertEquals(3, queue.size());
        List<String> words = queue.stream().map(Term::getWord).collect(Collectors.toList());
        assertTrue(words.contains("tom"));
        assertTrue(words.contains("alice"));
        assertTrue(words.contains("lucy"));
    }

    @Test
    void load_shouldIndexIntoSearchServiceWhenAddToSuggesterTrie() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, true));

        // with addToSuggesterTrie=true the SearchService prefix trie must be populated; a prefix
        // search on "to" should therefore surface the "tom" entry
        Map<Long, List<Long>> modelIdToDataSetIds = new java.util.HashMap<>();
        modelIdToDataSetIds.put(1L, java.util.Collections.singletonList(10L));

        List<HanlpMapResult> results = SearchService.prefixSearch("to", 10, modelIdToDataSetIds,
                java.util.Collections.emptySet());
        assertTrue(results.stream().anyMatch(r -> "tom".equals(r.getName())),
                "expected 'tom' to be indexed in SearchService after load: " + results);
    }
}
