package com.tencent.supersonic.headless.chat.knowledge;

import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.dictionary.CoreDictionary;
import com.hankcs.hanlp.seg.common.Term;
import com.tencent.supersonic.common.pojo.enums.DictWordType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证项目级自定义词典文件 {@code data/dictionary/custom/CustomDictionary.txt} ——即
 * {@code HanLP.Config.CustomDictionaryPath} 所指向的文件——能通过 {@link MultiCustomDictionary#load} 干净地加载。
 *
 * <p>
 * 该文件刻意覆盖了生产加载器支持的每一条代码路径：
 * <ul>
 * <li>数据集 / 指标 / 维度 / 术语名（中文，故 {@code isLetters == false}）</li>
 * <li>多词性词条（一行两对 nature/frequency）</li>
 * <li>小写字母维度值词（{@code tom}、{@code sales}…——{@code isLetters == false}）</li>
 * <li>大写字母维度值词（{@code HR}、{@code App}、{@code New#York}、{@code Taylor#Swift}）
 * ——{@code isLetters == true}，但 nature 末段为数字 dimId，故走 {@code addDimValueAlias} 分支且不会中止加载</li>
 * <li>含空格的值用 {@code #} 占位符写入</li>
 * </ul>
 * 关键点：文件中<strong>不含</strong> {@code #} 注释行，也<strong>不含</strong>带非数字 nature 后缀的大写词（如
 * {@code GMV _10_101_metric 100000}）。任一违规都会使 {@code load} 在解析词频 / {@code Long.parseLong} 时抛异常，并以
 * {@code return false} / {@code return true} 中止整个文件的加载。
 */
class CustomDictionaryFileTest {

    private static final String DICT_RESOURCE = "/data/dictionary/custom/CustomDictionary.txt";

    private String dictPath;

    @BeforeEach
    void setUp() throws Exception {
        URL url = getClass().getResource(DICT_RESOURCE);
        assertNotNull(url, "测试词典未在 classpath 上找到: " + DICT_RESOURCE);
        dictPath = new File(url.toURI()).getAbsolutePath();

        // 重置共享静态状态，保证每个测试相互独立
        MultiCustomDictionary.NATURE_TO_VALUES.clear();
        SearchService.clear();
        KnowledgeBaseService.removeDimValueAlias(null);
    }

    /** 加载整个文件必须成功，并解析出全部 54 个不重复的词。 */
    @Test
    void load_shouldParseEntireFileWithoutAborting() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();

        boolean success =
                MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false);

        assertTrue(success, "load() 返回 false —— 文件被拒绝");
        // 54 个不重复的词（无重复需合并），故 map 必须包含全部
        assertEquals(54, map.size(),
                "期望解析全部 54 个词，实际 " + map.size() + " —— 加载器可能中途中止。键集合: " + map.keySet());
        String treeGraph = buildDictionaryTree(map);
        System.out.println(treeGraph);
    }

    /** 每个语义类别都必须存在一个代表性词条。 */
    @Test
    void load_shouldContainEntriesFromEveryCategory() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        // DATASET —— nature 为 _<dataSetId>
        assertTrue(map.containsKey("销售分析"));
        assertEquals("_1", map.get("销售分析").nature[0].toString());
        // METRIC —— nature 为 _<modelId>_<elementId>_metric
        assertTrue(map.containsKey("销售额"));
        assertEquals("_1_101_metric", map.get("销售额").nature[0].toString());
        // DIMENSION —— nature 为 _<modelId>_<elementId>_dimension
        assertTrue(map.containsKey("部门"));
        assertEquals("_1_1_dimension", map.get("部门").nature[0].toString());
        // TERM —— nature 为 _<dataSetId>_<elementId>_term
        assertTrue(map.containsKey("环比增长"));
        assertEquals("_1_301_term", map.get("环比增长").nature[0].toString());
        // VALUE —— nature 为 _<modelId>_<elementId>（无后缀）
        assertTrue(map.containsKey("北京"));
        assertEquals("_1_201", map.get("北京").nature[0].toString());
    }

    /** 多词性词条必须携带两个 nature，且词频求和。 */
    @Test
    void load_shouldParseMultiNatureEntry() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        // 行: "访问 _1_106_metric 100000 _2_112_metric 100000"
        CoreDictionary.Attribute visit = map.get("访问");
        assertNotNull(visit);
        assertEquals(2, visit.nature.length);
        List<String> natures = Arrays.stream(visit.nature).map(Nature::toString).toList();
        assertTrue(natures.contains("_1_106_metric"));
        assertTrue(natures.contains("_2_112_metric"));
        assertEquals(100000 + 100000, visit.totalFrequency);
    }

    /**
     * 大写字母维度值词必须走 {@code isLetters} / {@code addDimValueAlias} 分支加载且不中止。 词本身保留原大小写（load 不做小写化），且
     * {@code #} 占位符在 map 的键中被还原为空格。
     */
    @Test
    void load_shouldAcceptUppercaseValueWordsAndRestoreSpacePlaceholder() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        // 单段大写维度值词保留原大小写
        assertTrue(map.containsKey("HR"), "HR 应在 map 中");
        assertTrue(map.containsKey("App"), "App 应在 map 中");
        assertEquals("_1_1", map.get("HR").nature[0].toString());
        assertEquals("_1_204", map.get("App").nature[0].toString());

        // 以 "#" 分隔的词在 map 键中被还原为空格
        assertTrue(map.containsKey("New York"), "New#York 应被还原为 'New York'");
        assertTrue(map.containsKey("San Francisco"));
        assertTrue(map.containsKey("Taylor Swift"));

        // 别名分支必须为每个 dimId 注册
        Map<Long, List<DictWord>> aliasMap = KnowledgeBaseService.getDimValueAlias();
        assertFalse(aliasMap.isEmpty(), "大写词应注册维度值别名");
        // HR -> dimId 1 ; App -> dimId 204 ; New York/San Francisco -> dimId 201 ; Taylor Swift
        // -> dimId 7
        assertTrue(aliasMap.containsKey(1L));
        assertTrue(aliasMap.containsKey(204L));
        assertTrue(aliasMap.containsKey(201L));
        assertTrue(aliasMap.containsKey(7L));
    }

    /** 文件中出现的 nature 必须在 NATURE_TO_VALUES 索引中建有对应条目。 */
    @Test
    void load_shouldPopulateNatureToValuesIndex() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_1_2"));
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_1_201"));
        // 术语词条携带完整 nature（_1_301_term），而非裸 _term
        assertTrue(MultiCustomDictionary.NATURE_TO_VALUES.containsKey("_1_301_term"));

        // nature _1_201 持有城市维度值: 北京/上海/广州/深圳（+New York/San Francisco）
        PriorityBlockingQueue<Term> queue = MultiCustomDictionary.NATURE_TO_VALUES.get("_1_201");
        assertNotNull(queue);
        List<String> words = queue.stream().map(Term::getWord).toList();
        assertTrue(words.contains("北京"));
        assertTrue(words.contains("上海"));
    }

    /** addToSuggesterTrie=true 时，SearchService 前缀 Trie 必须被填充。 */
    @Test
    void load_shouldIndexIntoSearchServiceWhenAddToSuggesterTrie() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, true));

        // "tom _1_2 52" —— model 1 映射到 dataSet 10
        Map<Long, List<Long>> modelIdToDataSetIds = new HashMap<>();
        modelIdToDataSetIds.put(1L, Collections.singletonList(10L));

        List<HanlpMapResult> results =
                SearchService.prefixSearch("to", 10, modelIdToDataSetIds, Collections.emptySet());
        assertTrue(results.stream().anyMatch(r -> "tom".equals(r.getName())),
                "加载后 'tom' 应被索引到 SearchService: " + results);
    }

    /**
     * 将加载后的词典渲染为文本树，按语义类别（DATASET / METRIC / DIMENSION / TERM / VALUE） → nature → 词 的层级分组。打印到
     * stdout 供人工查看，并断言输出包含根头、每个类别 及若干代表性词条。
     */
    @Test
    void buildTextTree_shouldRenderDictionaryGroupedByCategoryAndNature() {
        TreeMap<String, CoreDictionary.Attribute> map = new TreeMap<>();
        LinkedHashSet<Nature> natureCollector = new LinkedHashSet<>();
        assertTrue(MultiCustomDictionary.load(dictPath, Nature.n, map, natureCollector, false));

        String tree = buildDictionaryTree(map);
        // 打印到测试日志，便于人工目检结构
        System.out.println(tree);

        assertTrue(tree.contains("CustomDictionary"), "树应包含根头");
        assertTrue(tree.contains("54 words"), "根头应报告词数");
        // 文件中出现的每个语义类别都必须作为分支出现
        assertTrue(tree.contains("DATASET"));
        assertTrue(tree.contains("METRIC"));
        assertTrue(tree.contains("DIMENSION"));
        assertTrue(tree.contains("TERM"));
        assertTrue(tree.contains("VALUE"));
        // 代表性词、nature 和词频应可见
        assertTrue(tree.contains("销售额"));
        assertTrue(tree.contains("_1_101_metric"));
        assertTrue(tree.contains("freq="));
    }

    // ------------------------------------------------------------------
    // 树渲染辅助方法
    // ------------------------------------------------------------------

    /** 语义类别的固定显示顺序（无法归类的归入末尾的 OTHER）。 */
    private static final List<DictWordType> CATEGORY_ORDER = Arrays.asList(DictWordType.DATASET,
            DictWordType.METRIC, DictWordType.DIMENSION, DictWordType.TERM, DictWordType.VALUE);

    /** 仅用于把词典渲染为文本的简单树节点。 */
    private static final class TreeNode {
        final String label;
        final List<TreeNode> children = new ArrayList<>();

        TreeNode(String label) {
            this.label = label;
        }

        TreeNode child(String label) {
            TreeNode c = new TreeNode(label);
            children.add(c);
            return c;
        }
    }

    /**
     * 构建词典的文本树：根 → 类别 → nature → 词叶子。同一 nature 下的词按词频降序、 再按字母升序排列。{@code #} 空格占位符会被还原，使打印名与用户输入一致。
     */
    private String buildDictionaryTree(TreeMap<String, CoreDictionary.Attribute> map) {
        // 类别 -> nature -> 叶子列表
        Map<DictWordType, Map<String, List<Leaf>>> grouped = new LinkedHashMap<>();
        // getNatureType() 无法归类的 nature，放入合成的 "OTHER" 桶
        Map<String, List<Leaf>> other = new TreeMap<>();
        int pairCount = 0;

        for (Map.Entry<String, CoreDictionary.Attribute> e : map.entrySet()) {
            CoreDictionary.Attribute attr = e.getValue();
            for (int i = 0; i < attr.nature.length; i++) {
                pairCount++;
                String natureStr = attr.nature[i].toString();
                int freq = attr.frequency[i];
                String original =
                        (attr.originals != null && i < attr.originals.length) ? attr.originals[i]
                                : null;
                Leaf leaf = new Leaf(e.getKey(), freq, original);
                DictWordType type = DictWordType.getNatureType(natureStr);
                if (type == null) {
                    other.computeIfAbsent(natureStr, k -> new ArrayList<>()).add(leaf);
                } else {
                    grouped.computeIfAbsent(type, k -> new TreeMap<>())
                            .computeIfAbsent(natureStr, k -> new ArrayList<>()).add(leaf);
                }
            }
        }

        TreeNode root = new TreeNode(
                "CustomDictionary (" + map.size() + " words, " + pairCount + " word-nature pairs)");
        for (DictWordType type : CATEGORY_ORDER) {
            Map<String, List<Leaf>> byNature = grouped.get(type);
            if (byNature == null || byNature.isEmpty()) {
                continue;
            }
            int leaves = byNature.values().stream().mapToInt(List::size).sum();
            TreeNode cat = root.child(
                    type.name() + " (" + byNature.size() + " natures, " + leaves + " words)");
            for (Map.Entry<String, List<Leaf>> ne : byNature.entrySet()) {
                TreeNode nat = cat.child(ne.getKey());
                List<Leaf> sorted = new ArrayList<>(ne.getValue());
                // 词频降序，再按词升序，输出稳定可读
                sorted.sort(
                        (a, b) -> b.freq != a.freq ? b.freq - a.freq : a.word.compareTo(b.word));
                for (Leaf leaf : sorted) {
                    nat.child(formatLeaf(leaf));
                }
            }
        }
        if (!other.isEmpty()) {
            TreeNode cat = root.child("OTHER (" + other.size() + " natures)");
            for (Map.Entry<String, List<Leaf>> ne : other.entrySet()) {
                TreeNode nat = cat.child(ne.getKey());
                ne.getValue().forEach(l -> nat.child(formatLeaf(l)));
            }
        }

        StringBuilder sb = new StringBuilder();
        render(root, "", true, sb);
        return sb.toString();
    }

    /** (词, 词频, 原形) 三元组，保留以便按词频排序。 */
    private static final class Leaf {
        final String word;
        final int freq;
        final String original;

        Leaf(String word, int freq, String original) {
            this.word = word;
            this.freq = freq;
            this.original = original;
        }
    }

    /**
     * 一个词典叶子，如 {@code 销售额 (freq=100000)}；带原形时为 {@code hr (freq=876) ⟶ HR}。
     */
    private String formatLeaf(Leaf leaf) {
        // 还原 # 空格占位符，使打印名与用户输入一致
        String display = leaf.word.replace("#", " ");
        StringBuilder sb =
                new StringBuilder(display).append(" (freq=").append(leaf.freq).append(")");
        if (leaf.original != null && !leaf.original.equals(leaf.word)) {
            sb.append(" ⟶ ").append(leaf.original);
        }
        return sb.toString();
    }

    /** 使用框线字符的深度优先渲染器。 */
    private void render(TreeNode node, String prefix, boolean isTail, StringBuilder sb) {
        sb.append(prefix).append(isTail ? "└── " : "├── ").append(node.label).append('\n');
        for (int i = 0; i < node.children.size(); i++) {
            boolean childTail = (i == node.children.size() - 1);
            String childPrefix = prefix + (isTail ? "    " : "│   ");
            render(node.children.get(i), childPrefix, childTail, sb);
        }
    }
}
