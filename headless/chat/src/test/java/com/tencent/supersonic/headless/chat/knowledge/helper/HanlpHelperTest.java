package com.tencent.supersonic.headless.chat.knowledge.helper;

import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import com.tencent.supersonic.headless.api.pojo.response.S2Term;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link HanlpHelper#getTerms(String, Map)}. */
class HanlpHelperTest {

    private Segment segment;

    /**
     * Injects a mock {@link Segment} into the static {@code segment} field of {@link HanlpHelper}
     * so that {@link HanlpHelper#getTerms(String, Map)} can be exercised without loading the HanLP
     * core/custom dictionary data files (which are not present on this module's test classpath).
     */
    @BeforeEach
    void setUp() throws Exception {
        segment = mock(Segment.class);
        Field field = HanlpHelper.class.getDeclaredField("segment");
        field.setAccessible(true);
        field.set(null, segment);
    }

    @Test
    void getTerms_shouldLowerCaseInputFilterByNatureAndMapModelToDataSet() {
        // model 1 maps to dataSets 10 and 20
        Map<Long, List<Long>> modelIdToDataSetIds = new HashMap<>();
        modelIdToDataSetIds.put(1L, Arrays.asList(10L, 20L));

        // nature "_1_2" -> model 1, element 2; should fan out to dataSets 10/20
        Term modelTerm = new Term("tom", Nature.create("_1_2"));
        modelTerm.frequency = 5;
        modelTerm.offset = 0;
        // nature "n" does not start with "_" -> filtered out
        Term nonSemanticTerm = new Term("hello", Nature.create("n"));
        // nature "_term" is a TERM nature -> kept as-is, no model/dataset mapping
        Term termWord = new Term("concept", Nature.create("_term"));
        // nature "_9_2" -> model 9 is absent from the map -> dropped
        Term unmatchedModelTerm = new Term("jack", Nature.create("_9_2"));

        when(segment.seg("tom hello concept jack")).thenReturn(
                Arrays.asList(modelTerm, nonSemanticTerm, termWord, unmatchedModelTerm));

        List<S2Term> result = HanlpHelper.getTerms("TOM Hello Concept JACK", modelIdToDataSetIds);

        // the input text must be lower-cased before being handed to the segmenter
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(segment).seg(captor.capture());
        assertEquals("tom hello concept jack", captor.getValue());

        // modelTerm -> 2 S2Terms (dataSets 10 & 20), termWord -> 1 S2Term; the other two are
        // dropped
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(t -> "tom".equals(t.word) || "concept".equals(t.word)));

        // "tom" fans out to the two mapped dataSet natures, carrying over frequency
        List<String> tomNatures = result.stream().filter(t -> "tom".equals(t.word))
                .map(t -> t.nature.toString()).collect(Collectors.toList());
        assertEquals(2, tomNatures.size());
        assertTrue(tomNatures.contains("_10_2"));
        assertTrue(tomNatures.contains("_20_2"));
        result.stream().filter(t -> "tom".equals(t.word))
                .forEach(t -> assertEquals(5, t.getFrequency()));

        // "_term" nature is preserved unchanged
        S2Term conceptTerm =
                result.stream().filter(t -> "concept".equals(t.word)).findFirst().orElseThrow();
        assertEquals("_term", conceptTerm.nature.toString());
    }

    @Test
    void getTerms_shouldReturnEmptyWhenModelHasNoDataSetMapping() {
        Map<Long, List<Long>> modelIdToDataSetIds = new HashMap<>();
        // model 1 maps to no dataSet
        modelIdToDataSetIds.put(1L, Collections.emptyList());

        Term term = new Term("tom", Nature.create("_1_2"));
        when(segment.seg("tom")).thenReturn(Collections.singletonList(term));

        List<S2Term> result = HanlpHelper.getTerms("tom", modelIdToDataSetIds);
        assertTrue(result.isEmpty());
    }
}
