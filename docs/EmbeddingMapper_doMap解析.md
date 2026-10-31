# EmbeddingMapper.doMap 执行过程解析

> 文件：`headless/chat/src/main/java/com/tencent/supersonic/headless/chat/mapper/EmbeddingMapper.java`
> 作用：基于**向量嵌入（Embedding）**识别用户查询中命中的 Schema 元素（指标 / 维度 / 实体 / 值等），并将其归并到 `SchemaMapInfo`，供后续语义解析阶段使用。

---

## 一、定位与触发条件

`EmbeddingMapper` 继承自 `BaseMapper`，是 Schema Mapper 链路中的一个具体实现。其执行入口不是 `doMap` 本身，而是父类的 `map()` 模板方法。

### 1.1 模板方法 `BaseMapper.map()`

```java
public void map(ChatQueryContext chatQueryContext) {
    if (!accept(chatQueryContext)) return;      // ① 前置校验
    long startTime = System.currentTimeMillis();
    try {
        doMap(chatQueryContext);                 // ② 子类核心逻辑
        MapFilter.filter(chatQueryContext);      // ③ 后置过滤
    } catch (Exception e) {
        log.error("work error", e);              // 异常被吞掉，不中断整条链路
    }
}
```

- `accept()` 返回 `false` 时直接跳过本 Mapper。
- `doMap()` 抛出的任何异常都被 catch，**不会影响其它 Mapper 的执行**。
- `doMap()` 完成后会调用 `MapFilter.filter()` 对已收集的匹配结果做过滤。

### 1.2 `accept()` 触发条件

```java
boolean b0 = MapModeEnum.LOOSE.equals(...getMapModeEnum());
boolean b1 = ...getText2SQLType() == Text2SQLType.LLM_OR_RULE;
return b0 || b1;
```

满足以下任一条件即执行：

| 条件 | 含义 |
|------|------|
| `MapModeEnum.LOOSE` | 宽松匹配模式（召回优先，阈值低） |
| `Text2SQLType.LLM_OR_RULE` | 走“LLM 或规则”解析路径 |

> 源码 TODO 注释指出：如果已经在 `LOOSE` 阶段执行过，`LLM_OR_RULE` 阶段存在重复执行的浪费，目前缺少状态传递机制，暂时忽略。

---

## 二、doMap 整体流程概览

```
doMap(chatQueryContext)
│
├─ 1. 取出 SchemaMapInfo（已映射信息容器）
│
├─ 2. getMatches()  ── 向量召回
│     ├─ HanlpHelper.getTerms()      分词 + 词性过滤 + 数据集过滤
│     ├─ EmbeddingMatchStrategy.match()
│     │     └─ detect()
│     │           ├─ BatchMatchStrategy.detect()   滑动窗口切分文本段
│     │           │     └─ detectByBatch()
│     │           │           ├─ 按 EMBEDDING_MAPPER_BATCH 分批
│     │           │           ├─ executeTasks() 多线程并发检索（mapExecutor 线程池）
│     │           │           │     └─ detectByQueryTextsSub()
│     │           │           │           ├─ metaEmbeddingService.retrieveQuery() 向量库检索
│     │           │           │           ├─ 阈值过滤（非 LLM 模式）
│     │           │           │           ├─ convertToEmbeddingResult() 转换结果
│     │           │           │           └─ 排序 + 截断 (embeddingRoundNumber * batch)
│     │           │           └─ selectResultInOneRound() 合并去重
│     │           ├─ (可选) detectWithLLM()  LLM 增强召回
│     │           └─ distinct() 去重
│     └─ 取首个非空结果列表
│
├─ 3. HanlpHelper.transLetterOriginal()  ── 字母词还原原形
│
├─ 4. 遍历 matchResults，逐条构建 SchemaElementMatch
│     ├─ Retrieval.getLongId(id)        解析 elementId
│     ├─ metadata 中取 dataSetId / type
│     ├─ getSchemaElement()             从 SemanticSchema 查实际元素
│     ├─ SchemaElementMatch.builder()   组装匹配对象
│     └─ addToSchemaMap()               写入 SchemaMapInfo（高相似度覆盖低相似度）
│
└─ 5. 日志输出（命中明细 / no match）
```

---

## 三、分步详解

### 步骤 1：获取已映射信息容器

```java
SchemaMapInfo mappedInfo = chatQueryContext.getMapInfo();
```

`SchemaMapInfo` 内部维护 `Map<Long, List<SchemaElementMatch>>` —— **以 dataSetId 为 key，每个数据集下挂载命中的元素匹配列表**。本 Mapper 的产出会写入这个结构，并和前面其它 Mapper 的产出合并。

### 步骤 2：向量召回 —— `getMatches()`

> 定义在父类 `BaseMapper` 中，是通用的“分词 + 策略匹配 + 取首个结果”流程。

```java
public <T> List<T> getMatches(ChatQueryContext chatQueryContext, MatchStrategy matchStrategy) {
    String queryText = chatQueryContext.getRequest().getQueryText();
    List<S2Term> terms = HanlpHelper.getTerms(queryText, chatQueryContext.getModelIdToDataSetIds());
    terms = HanlpHelper.getTerms(terms, chatQueryContext.getRequest().getDataSetIds());
    Map<MatchText, List<T>> matchResult =
            matchStrategy.match(chatQueryContext, terms, chatQueryContext.getRequest().getDataSetIds());
    // 取首个非空 entry 的结果列表
    ...
}
```

#### 2.1 HanLP 分词（`HanlpHelper.getTerms`）

- 第一重载：对 `queryText.toLowerCase()` 用 HanLP 分词；只保留词性以 `NATURE_SPILT` 开头（即业务自定义词典词性）的词；通过 `transform2ApiTerm` 把 model 维度的词性映射成 dataSet 维度的 `S2Term`。
- 第二重载：按请求指定的 `dataSetIds` 过滤 term，只保留属于目标数据集的词。

#### 2.2 `EmbeddingMatchStrategy.match()` → `detect()`

`EmbeddingMatchStrategy` 继承自 `BatchMatchStrategy`（→ `BaseMatchStrategy`）。`match()` 把整句 `queryText` 作为 `MatchText`，结果列表由 `detect()` 产生。

`detect()` 三段式：

```java
// 1. 基础检测（滑窗切分 + 批量向量检索）
List<EmbeddingResult> baseResults = super.detect(...);
// 2. 可选 LLM 增强检测
if (useLLM) { baseResults.addAll(detectWithLLM(...)); }
// 3. 去重
return baseResults.stream().distinct().collect(Collectors.toList());
```

#### 2.3 基础检测：滑动窗口切分（`BatchMatchStrategy.detect`）

按配置 `EMBEDDING_MAPPER_TEXT_SIZE`（窗口大小）与 `EMBEDDING_MAPPER_TEXT_STEP`（步长），在原 queryText 上滑动截取子串，构成 `detectSegments` 集合，交给 `detectByBatch`。

#### 2.4 批量并发检索（`detectByBatch`）

- 按 `EMBEDDING_MAPPER_BATCH` 把 segments 分成多个子批。
- 每个子批构造一个 `Supplier` 任务，通过 `executeTasks()` 提交到 `mapExecutor` 线程池并发执行。
- `executeTasks()` 用 `CompletableFuture.allOf` 等待全部完成，扁平化后通过 `selectResultInOneRound()` 按 `lessOrEqualSimilar` 规则合并去重（相似度低的被相似度高的替换）。

#### 2.5 单批检索（`detectByQueryTextsSub`）

```java
double threshold  = EMBEDDING_MAPPER_THRESHOLD;     // 相似度阈值
int embeddingNumber = EMBEDDING_MAPPER_NUMBER;      // 每次召回 topN
int embeddingRoundNumber = EMBEDDING_MAPPER_ROUND_NUMBER;

RetrieveQuery retrieveQuery = RetrieveQuery.builder().queryTextsList(queryTextsSub).build();
List<RetrieveQueryResult> retrieveQueryResults =
        metaEmbeddingService.retrieveQuery(retrieveQuery, embeddingNumber,
                modelIdToDataSetIds, detectDataSetIds);   // 向量库检索
```

结果处理：

1. **阈值过滤**（仅非 LLM 模式）：若检索词未包含在 query 中且相似度低于 `threshold`，则移除。
2. **转换**：`convertToEmbeddingResult()` 把 `Retrieval` 转成 `EmbeddingResult`，其中 `detectWord` = 该批 query，`name` = `retrieval.getQuery()`（schema 元素名），`metadata` 全部转成 `String` 值。
3. **排序 + 截断**：按 similarity 降序，最多保留 `embeddingRoundNumber * queryTextsSub.size()` 条。

#### 2.6 LLM 增强检测（`detectWithLLM`，受 `EMBEDDING_MAPPER_USE_LLM` 控制）

- 用 `extractValidSegments()` 对 query 重新分词，过滤掉 `EMBEDDING_MAPPER_ALLOWED_SEGMENT_NATURE` 中指定词性的词，得到有效片段。
- 走 `detectByBatch(..., useLlm=true)` 进行向量召回。
- 召回后用 `LLM_FILTER_PROMPT` 调用大模型（`ModelProvider.getChatModel`），让 LLM 从候选中筛选出与用户意图最相关的元素 ID 列表。
- 用 LLM 返回的 ID 集合过滤结果，并把保留下来的结果标记 `setLlmMatched(true)`。

> LLM 模型配置优先取请求中 `ChatAppConfig` 的 `REWRITE_MULTI_TURN` 应用配置。

#### 2.7 取首个非空结果

`getMatches` 在 `match()` 返回的 `Map<MatchText, List<T>>` 中取**第一个非空列表**返回给 `doMap`。

### 步骤 3：字母词还原原形 —— `HanlpHelper.transLetterOriginal()`

```java
HanlpHelper.transLetterOriginal(matchResults);
```

针对结果中**纯小写字母**且**自定义词典中存在**的 `name`，取其 `originals`（原形列表），为每个原形克隆出一个新的 `EmbeddingResult`（保留 id / metadata / similarity / detectWord，仅替换 name），从而把“缩写/小写形式”还原为原始名称，扩大后续匹配覆盖面。非字母词原样保留。

### 步骤 4：构建并归并 `SchemaElementMatch`

对每条 `EmbeddingResult`：

```java
Long elementId = Retrieval.getLongId(matchResult.getId());           // id 取 "_" 前的数字部分
Long dataSetId = Retrieval.getLongId(matchResult.getMetadata().get("dataSetId"));
if (dataSetId == null) continue;                                      // ① 数据集缺失则跳过

SchemaElementType elementType =
        SchemaElementType.valueOf(matchResult.getMetadata().get("type"));
SchemaElement schemaElement = getSchemaElement(dataSetId, elementType, elementId,
        chatQueryContext.getSemanticSchema());                        // ② 从语义 schema 查元素
if (schemaElement == null) continue;                                  //    查不到则跳过

SchemaElementMatch schemaElementMatch = SchemaElementMatch.builder()
        .element(schemaElement)
        .frequency(BaseWordBuilder.DEFAULT_FREQUENCY)
        .word(matchResult.getName())
        .similarity(matchResult.getSimilarity())
        .detectWord(matchResult.getDetectWord())
        .build();
schemaElementMatch.setLlmMatched(matchResult.isLlmMatched());         // ③ 标记是否 LLM 召回

addToSchemaMap(chatQueryContext.getMapInfo(), dataSetId, schemaElementMatch);  // ④ 归并
```

要点：

- **`Retrieval.getLongId`**：`id` 形如 `12345_xxx`，取 `_` 前缀解析为 `Long`；空/空白返回 `null`。
- **`getSchemaElement`**：从 `SemanticSchema` 中按 `(dataSetId, elementType, elementId)` 取出 `SchemaElement`，做属性拷贝；对 `VALUE` 类型还会按 `name` 过滤 alias。查不到返回 `null`，本条跳过。
- **`SchemaElementMatch` 字段含义**：

  | 字段 | 含义 |
  |------|------|
  | `element` | 命中的 Schema 元素（指标/维度/实体/值） |
  | `word` | 匹配到的词（embedding name / 还原后的原形） |
  | `detectWord` | 触发命中的检测词（query 子串） |
  | `similarity` | 向量相似度 |
  | `frequency` | 默认词频 `BaseWordBuilder.DEFAULT_FREQUENCY` |
  | `llmMatched` | 是否由 LLM 增强召回 |

### 步骤 4.1：归并策略 —— `addToSchemaMap()`

将 `SchemaElementMatch` 写入 `dataSetId -> List<SchemaElementMatch>` 时，执行**高相似度覆盖**去重：

```java
schemaElementMatches.removeIf(existing -> {
    if (isEquals(existing, newElementMatch)) {            // 同一元素才算重复
        if (newElementMatch.getSimilarity() > existing.getSimilarity()) {
            return true;                                   // 新的更相似 → 删旧的
        } else {
            shouldAddNew.set(false);                       // 旧的更相似 → 不加入新的
        }
    }
    return false;
});
if (shouldAddNew.get()) schemaElementMatches.add(newElementMatch);
```

`isEquals` 判定：

- `element` 相等（`SchemaElement.equals`）；且
- 若是 `VALUE` 类型，额外要求 `word` 忽略大小写相等。

即：**同一元素只保留相似度最高的一条匹配**；不同元素或不同 word 各自独立保留。

### 步骤 5：日志输出

- 无命中：`embedding mapper no match`。
- 有命中：逐条打印 `name / detectWord / similarity / metadata`，便于调试。

---

## 四、关键配置项（`MapperConfig`）

| 配置键 | 作用 |
|--------|------|
| `EMBEDDING_MAPPER_TEXT_SIZE` | 滑动窗口大小（切分 queryText 的子串长度） |
| `EMBEDDING_MAPPER_TEXT_STEP` | 滑动窗口步长 |
| `EMBEDDING_MAPPER_BATCH` | 向量检索分批大小 |
| `EMBEDDING_MAPPER_NUMBER` | 每次 retrieveQuery 的 topN 召回数 |
| `EMBEDDING_MAPPER_THRESHOLD` | 相似度阈值（非 LLM 模式下过滤） |
| `EMBEDDING_MAPPER_ROUND_NUMBER` | 轮数因子，与批大小共同决定最终保留条数上限 |
| `EMBEDDING_MAPPER_USE_LLM` | 是否启用 LLM 增强召回 |
| `EMBEDDING_MAPPER_ALLOWED_SEGMENT_NATURE` | LLM 模式下需排除的词性列表 |

---

## 五、数据结构流转图

```
queryText
   │
   ▼ HanLP 分词 + 词性/数据集过滤
List<S2Term>
   │
   ▼ match() 包装为 MatchText
Map<MatchText, List<EmbeddingResult>>  ──(向量库检索 + 阈值过滤 + 排序截断 + LLM 可选增强)
   │
   ▼ 取首个非空
List<EmbeddingResult>  ──(transLetterOriginal 还原原形，可能扩展条目)
   │
   ▼ 逐条解析 elementId / dataSetId / type
SchemaElement  ──(getSchemaElement 从 SemanticSchema 查出)
   │
   ▼ 组装
List<SchemaElementMatch>  ──(addToSchemaMap 高相似度覆盖归并)
   │
   ▼
SchemaMapInfo.dataSetElementMatches : Map<Long, List<SchemaElementMatch>>
```

---

## 六、小结

`doMap` 的核心职责是 **“用向量相似度把用户查询中的自然语言片段对齐到语义模型的 Schema 元素”**，其设计要点：

1. **模板方法分层**：`BaseMapper.map()` 统一控制 `accept → doMap → MapFilter` 与异常隔离，子类只实现 `doMap`。
2. **召回-过滤-增强三段式**：滑窗切分 → 并发向量检索 →（可选）LLM 精排，兼顾召回率与精度。
3. **并发执行**：通过 `mapExecutor` 线程池 + `CompletableFuture` 并发处理多个查询子批。
4. **结果归并**：以 `(dataSetId, element, [value-word])` 为粒度，仅保留相似度最高的匹配，避免重复元素污染后续解析。
5. **健壮性**：`dataSetId` 缺失、`schemaElement` 查不到、异常等均被静默跳过 / 捕获，保证链路不被单点故障打断。

最终产出的 `SchemaMapInfo` 将作为后续 **Semantic Parser**（规则/LLM 生成 S2SQL）的输入，决定 Text2SQL 能否正确选择指标、维度与过滤条件。
