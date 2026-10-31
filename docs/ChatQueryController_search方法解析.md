# ChatQueryController.search 方法实现逻辑深度分析

> 分析对象：`com.tencent.supersonic.chat.server.rest.ChatQueryController#search`
> 入口路径：`POST /api/chat/query/search`、`POST /openapi/chat/query/search`
> 作用：根据用户自然语言查询文本，在语义层中检索推荐维度/指标/维度值，返回 `List<SearchResult>`，用于前端联想/纠错推荐。

---

## 一、Controller 层入口

文件：`chat/server/src/main/java/com/tencent/supersonic/chat/server/rest/ChatQueryController.java`

```java
@PostMapping("search")
public Object search(@RequestBody ChatParseReq chatParseReq, HttpServletRequest request,
        HttpServletResponse response) {
    chatParseReq.setUser(UserHolder.findUser(request, response));
    return chatQueryService.search(chatParseReq);
}
```

`@RequestMapping` 为 `{"/api/chat/query", "/openapi/chat/query"}`，因此 `search` 实际暴露两个等价端点。Controller 层职责极薄，仅做两件事：

1. **解析当前用户**：通过 `UserHolder.findUser(request, response)` 从请求（Token / Header / Session 等，由 SPI 实现决定）中识别用户，并叠加系统管理员判定（见 `UserHolder.getUser`：若用户名命中 `SystemConfig.getAdmins()`，则置 `isAdmin=1`），再把 `User` 写回 `chatParseReq`。后续的语义检索并不会直接消费该 User，但它会随 `QueryNLReq` 透传到语义层，影响数据集可见性等鉴权逻辑。
2. **委托给 Service**：直接调用 `chatQueryService.search(chatParseReq)`，返回类型声明为 `Object`，实际为 `List<SearchResult>`。

请求体 `ChatParseReq` 关键字段（`chat/api/.../request/ChatParseReq.java`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `queryText` | String | 用户输入的自然语言（检索的唯一文本来源） |
| `chatId` | Integer | 会话 ID |
| `agentId` | Integer | Agent ID，决定是否启用 search、可用数据集范围、ChatApp 配置 |
| `dataSetId` | Long | 指定数据集（可选，用于收窄检索范围） |
| `user` | User | Controller 注入 |
| `queryFilters` | QueryFilters | 查询过滤条件，参与维度值过滤 |
| `disableLLM` | boolean | 是否禁用 LLM（影响 `Text2SQLType` 取值） |
| `selectedParse` | SemanticParseInfo | 已选解析（透传到 `QueryNLReq.selectedParseInfo`） |

---

## 二、Service 层：ChatQueryServiceImpl#search

文件：`chat/server/src/main/java/com/tencent/supersonic/chat/server/service/impl/ChatQueryServiceImpl.java`

```java
@Override
public List<SearchResult> search(ChatParseReq chatParseReq) {
    ParseContext parseContext = buildParseContext(chatParseReq, null);
    Agent agent = parseContext.getAgent();
    if (!agent.enableSearch()) {
        return Lists.newArrayList();
    }
    QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
    return chatLayerService.retrieve(queryNLReq);
}
```

三步逻辑：

### 2.1 构建 ParseContext

`buildParseContext(chatParseReq, null)`：

```java
private ParseContext buildParseContext(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
    ParseContext parseContext = new ParseContext(chatParseReq, chatParseResp);
    Agent agent = agentService.getAgent(chatParseReq.getAgentId());
    parseContext.setAgent(agent);
    return parseContext;
}
```

- `ParseContext`（`chat/server/.../pojo/ParseContext.java`）是一个轻量上下文容器，持有 `request(ChatParseReq)`、`response(ChatParseResp)`、`agent(Agent)`。
- 注意 search 传入的 `chatParseResp` 为 `null`，意味着 `ParseContext.response` 为空——这与 `parse` 流程不同，search 不产生解析结果，仅做检索。
- 通过 `agentService.getAgent(agentId)` 加载 Agent 配置；若 `agentId` 为空或 Agent 不存在，`agent` 可能为 `null`，后续 `agent.enableSearch()` 会 NPE（这是一个潜在风险点，依赖上游保证 agentId 有效）。

### 2.2 Agent 开关校验

```java
if (!agent.enableSearch()) {
    return Lists.newArrayList();
}
```

`Agent.enableSearch()`（`chat/server/.../agent/Agent.java`）：

```java
private Integer enableSearch = ENABLED;   // 默认 1 = 开启
public boolean enableSearch() {
    return enableSearch == ENABLED;       // == 1
}
```

- Agent 默认开启 search（`enableSearch=1`）。
- 若管理员在 Agent 配置中关闭 search，则直接返回空列表，不再进入语义层。这是 search 的第一个早退分支。

### 2.3 转换为 QueryNLReq

`QueryReqConverter.buildQueryNLReq(parseContext)`（`chat/server/.../util/QueryReqConverter.java`）：

```java
public static QueryNLReq buildQueryNLReq(ParseContext parseContext) {
    QueryNLReq queryNLReq = new QueryNLReq();
    BeanMapper.mapper(parseContext.getRequest(), queryNLReq);
    queryNLReq.setText2SQLType(
            parseContext.enableLLM() ? Text2SQLType.LLM_OR_RULE : Text2SQLType.ONLY_RULE);
    queryNLReq.setDataSetIds(getDataSetIds(parseContext));
    queryNLReq.setChatAppConfig(parseContext.getAgent().getChatAppConfig());
    queryNLReq.setSelectedParseInfo(parseContext.getRequest().getSelectedParse());
    return queryNLReq;
}
```

要点：

1. **字段映射**：`BeanMapper.mapper` 把 `ChatParseReq` 的同名字段（`queryText`、`chatId`、`user`、`queryFilters`、`saveAnswer`、`queryId` 等）拷贝到 `QueryNLReq`。
2. **Text2SQLType**：`ParseContext.enableLLM()` 即 `!request.isDisableLLM()`。未禁用 LLM → `LLM_OR_RULE`；禁用 → `ONLY_RULE`。该枚举主要影响后续 parse/execute 的策略选择，对 retrieve 检索本身影响较小。
3. **数据集范围裁剪** `getDataSetIds`：
   ```java
   Set<Long> dataSetIds = parseContext.getAgent().getDataSetIds();   // Agent 允许的数据集
   Long requestDataSetId = chatParseReq.getDataSetId();              // 请求显式指定
   if (Objects.nonNull(requestDataSetId)) {
       if (CollectionUtils.isEmpty(dataSetIds)) {
           return Collections.singleton(requestDataSetId);           // Agent 无限制 → 用指定值
       }
       dataSetIds.removeIf(dataSetId -> !dataSetId.equals(requestDataSetId)); // 取交集
   }
   ```
   即「Agent 可见数据集」∩「请求指定数据集」，结果写入 `QueryNLReq.dataSetIds`。**这是检索范围的关键控制点**——为空时下游会直接返回空。
4. **ChatAppConfig / selectedParseInfo** 透传。

`QueryNLReq`（`headless/api/.../request/QueryNLReq.java`）继承 `SemanticQueryReq`，关键检索相关字段：`queryText`、`dataSetIds`、`queryFilters`、`mapModeEnum(默认 STRICT)`、`queryDataType(默认 ALL)`、`contextParseInfo`、`selectedParseInfo`。

### 2.4 委托语义层检索

```java
return chatLayerService.retrieve(queryNLReq);
```

---

## 三、语义层：S2ChatLayerService#retrieve

文件：`headless/server/src/main/java/com/tencent/supersonic/headless/server/facade/service/impl/S2ChatLayerService.java`

```java
@Override
public List<SearchResult> retrieve(QueryNLReq queryNLReq) {
    return retrieveService.retrieve(queryNLReq);
}
```

`S2ChatLayerService` 仅做一层转发，真正逻辑在 `RetrieveServiceImpl`。注意与 `map`/`parse` 的区别：retrieve 不走 `ChatWorkflowEngine`、不调用 SchemaMapper 链，而是直接基于知识库（HanLP 词典 + 嵌入）做匹配。

---

## 四、核心检索：RetrieveServiceImpl#retrieve

文件：`headless/server/src/main/java/com/tencent/supersonic/headless/server/service/impl/RetrieveServiceImpl.java`

常量：`RESULT_SIZE = 10`（最终最多返回 10 条）。

### 4.1 总体流程

```java
public List<SearchResult> retrieve(QueryNLReq queryNLReq) {
    if (CollectionUtils.isEmpty(queryNLReq.getDataSetIds())) {
        return Collections.emptyList();           // 早退 1：无数据集范围
    }
    String queryText = queryNLReq.getQueryText();

    // 1. 取元信息
    SemanticSchema semanticSchemaDb = schemaService.getSemanticSchema(queryNLReq.getDataSetIds());
    Map<Long, String> dataSetIdToName = semanticSchemaDb.getDataSetIdToName();
    Map<Long, List<Long>> modelIdToDataSetIds =
            dataSetService.getModelIdToDataSetIds(new ArrayList<>(dataSetIdToName.keySet()),
                    User.getDefaultUser());

    // 2. 分词检索
    List<S2Term> originals = knowledgeBaseService.getTerms(queryText, modelIdToDataSetIds);
    ...
    ChatQueryContext chatQueryContext = new ChatQueryContext(queryNLReq);
    chatQueryContext.setModelIdToDataSetIds(modelIdToDataSetIds);
    Map<MatchText, List<HanlpMapResult>> regTextMap =
            searchMatchStrategy.match(chatQueryContext, originals, dataSetIds);
    regTextMap.values().forEach(HanlpHelper::transLetterOriginal);

    // 3. 选取最长匹配段（最匹配）
    Optional<...> mostSimilarSearchResult = regTextMap.entrySet().stream()
            .filter(entry -> CollectionUtils.isNotEmpty(entry.getValue()))
            .max(Comparator.comparingInt(entry -> entry.getKey().getDetectSegment().length()));
    if (!mostSimilarSearchResult.isPresent()) {
        return Collections.emptyList();           // 早退 2：无任何匹配
    }
    ...
    // 4. 数据集候选 & 统计
    DataSetInfoStat dataSetInfoStat = NatureHelper.getDataSetStat(originals);
    List<Long> possibleDataSets = getPossibleDataSets(queryNLReq, originals, dataSetIds);

    // 5.1 优先匹配维度/指标
    Set<SearchResult> searchResults = searchMetricAndDimension(...);
    boolean existMetricAndDimension = CollectionUtils.isNotEmpty(searchResults);

    // 5.2 再按维度值补全
    Map<String, String> natureToNameMap = getNatureToNameMap(...);
    for (...) {
        searchResults.addAll(searchDimensionValue(...));
    }

    // 6. 截断返回
    return searchResults.stream().limit(RESULT_SIZE).collect(Collectors.toList());
}
```

### 4.2 分步解析

#### Step 1 — 加载语义元信息
- `schemaService.getSemanticSchema(dataSetIds)`：获取这些数据集下的 `SemanticSchema`（含所有维度/指标/实体 `SchemaElement`，及 `dataSetIdToName` 映射）。
- `dataSetService.getModelIdToDataSetIds(...)`：构建 `modelId -> dataSetIds` 反向映射，供 HanLP 词典检索时把模型维度归并到数据集。注意这里用 `User.getDefaultUser()`（系统默认用户）取映射，而非请求用户。

#### Step 2 — 分词与词典匹配
- `knowledgeBaseService.getTerms(queryText, modelIdToDataSetIds)`：基于 HanLP 自定义词典对 `queryText` 分词，产出 `List<S2Term>`，每个 term 带有 `nature`（词性，编码了元素类型 + dataSetId）。
- `searchMatchStrategy.match(chatQueryContext, originals, dataSetIds)`：在数据集范围内对分词结果做匹配，返回 `Map<MatchText, List<HanlpMapResult>>`——键是「匹配到的文本片段（含 `detectSegment` 与 `regText`）」，值是该片段命中的词典候选 `HanlpMapResult`（每个含 `name` 与多个 `natures`）。
- `HanlpHelper::transLetterOriginal`：对字母做大小写归一处理。

#### Step 3 — 选最优匹配段
- 在所有非空匹配中，按 `detectSegment.length()` 取**最长**的一段作为最匹配结果。这是 search 的核心排序策略：**倾向于返回覆盖用户输入最多字符的匹配**。
- 无匹配 → 返回空列表（早退 2）。

#### Step 4 — 候选数据集
`getPossibleDataSets`：
```java
if (CollectionUtils.isNotEmpty(dataSetIds)) {
    return new ArrayList<>(dataSetIds);   // 有显式范围 → 直接用
}
List<Long> possibleDataSets = NatureHelper.selectPossibleDataSets(originals); // 按 nature 推断
if (possibleDataSets.isEmpty() && queryReq.getContextParseInfo() != null) {
    possibleDataSets.add(queryReq.getContextParseInfo().getDataSetId());      // 回退到上下文数据集
}
```
由于 search 入口 `dataSetIds` 来自 Agent/请求，通常非空，直接返回。

#### Step 5.1 — 维度/指标匹配 `searchMetricAndDimension`
- 遍历最优段下的每个 `HanlpMapResult`，把其 `natures` 解析成 `DataSetWithSemanticType(dataSetId, elementType)`。
- `matchCondition` 过滤：只保留 `METRIC` 或 `DIMENSION` 类型，且在 `possibleDataSets` 范围内。
- 每个命中生成一条 `SearchResult`：
  - `recommend = matchText.getRegText() + hanlpMapResult.getName()`
  - `subRecommend = hanlpMapResult.getName()`
  - `dataSetId / dataSetName / schemaElementType`
- 结果用 `LinkedHashSet` 去重（`SearchResult.equals/hashCode` 基于 `recommend + dataSetName`）。

#### Step 5.2 — 维度值补全 `searchDimensionValue`
当用户输入的是「维度值」（如具体某个品牌名）而非维度/指标名本身时，走该分支补全：

- `getNatureToNameMap`：把候选 `natures` 过滤到 `possibleDataSets` 范围，转成 `nature -> wordName` 的有序映射。
- 对每个 `(nature, wordName)`：
  - `NatureHelper.getDataSetId(nature)` / `convertToElementType(nature)` 解析数据集与元素类型。
  - 构建 `baseSearchResult`（`recommend = regText + wordName`）。
  - `shouldCompleteMetricInfo(metricModelCount <= 0 && !existMetricAndDimension)`：**仅当 5.1 没有命中任何指标/维度、且当前没有指标统计时**，才进入「补指标」逻辑——用维度值 + 热门指标拼成推荐项（`isComplete=false`），帮用户补全成可查询的句子。
  - 补指标时：`calculateMetricSize = max(10 / natureToNameMap.size(), 1)`，按 `useCnt` 倒序取 topN 指标名，逐个生成 `createMetricSearchResult`（`recommend = regText + wordName + SPACE + metric`）。
  - `filterByQueryFilter`：若 `queryFilters` 中已存在该维度值，则跳过（避免重复推荐已选条件）。

#### Step 6 — 截断
`searchResults.stream().limit(10)`，最多返回 10 条 `SearchResult`。

---

## 五、返回结构 SearchResult

文件：`headless/api/.../response/SearchResult.java`

| 字段 | 类型 | 含义 |
|---|---|---|
| `recommend` | String | 推荐完整文本（拼好的可查询短语） |
| `subRecommend` | String | 子推荐（核心匹配词） |
| `dataSetId` | Long | 命中数据集 ID |
| `dataSetName` | String | 命中数据集名 |
| `schemaElementType` | SchemaElementType | 元素类型（METRIC/DIMENSION/VALUE/TERM 等） |
| `isComplete` | boolean | 是否为完整可查询项，默认 true；维度值+指标拼装时为 false |

`equals/hashCode` 仅基于 `recommend + dataSetName`，用于去重。

---

## 六、完整调用链路图

```
HTTP POST /api/chat/query/search  (或 /openapi/chat/query/search)
        │
        ▼
ChatQueryController.search
  ├── UserHolder.findUser(request, response)  → 注入 User 到 ChatParseReq
  └── chatQueryService.search(chatParseReq)
        ▼
ChatQueryServiceImpl.search
  ├── buildParseContext(req, null)
  │     ├── new ParseContext(req, null)
  │     └── agentService.getAgent(agentId) → 设置 Agent
  ├── agent.enableSearch()?  否 → return []
  ├── QueryReqConverter.buildQueryNLReq(parseContext)
  │     ├── BeanMapper.mapper(req → queryNLReq)
  │     ├── text2SQLType = enableLLM ? LLM_OR_RULE : ONLY_RULE
  │     ├── dataSetIds = Agent.dataSetIds ∩ req.dataSetId
  │     ├── chatAppConfig
  │     └── selectedParseInfo
  └── chatLayerService.retrieve(queryNLReq)
        ▼
S2ChatLayerService.retrieve  → retrieveService.retrieve(queryNLReq)
        ▼
RetrieveServiceImpl.retrieve
  0. dataSetIds 空? → return []
  1. schemaService.getSemanticSchema / dataSetService.getModelIdToDataSetIds
  2. knowledgeBaseService.getTerms(queryText)  → S2Term 分词
     searchMatchStrategy.match(...)            → Map<MatchText, List<HanlpMapResult>>
  3. 取 detectSegment 最长的匹配; 无 → return []
  4. getPossibleDataSets / NatureHelper.getDataSetStat
  5.1 searchMetricAndDimension  → 维度/指标命中
  5.2 searchDimensionValue      → 维度值 + 热门指标补全
  6. limit(10) → List<SearchResult>
```

---

## 七、关键设计要点与风险点

1. **职责分层清晰**：Controller 仅鉴权+注入 User；Service 编排上下文与开关；`S2ChatLayerService` 作 Facade 转发；`RetrieveServiceImpl` 承载全部检索算法。search 与 parse/execute 共用 `ChatParseReq`、`ParseContext`、`QueryNLReq`，但走完全不同的下游（不触发工作流引擎）。

2. **检索范围双重收窄**：Agent 的 `dataSetIds` ∩ 请求 `dataSetId`，再在 `RetrieveServiceImpl` 中作为 `possibleDataSets` 过滤候选 nature。空集直接早退。

3. **排序策略简单但有效**：以「匹配段长度最长」为唯一排序键，优先返回覆盖用户输入最多的推荐；指标补全时按 `useCnt`（使用频次）倒序，体现「热门优先」。

4. **去重依赖 `recommend+dataSetName`**：`SearchResult` 的 equals/hashCode 只看这两个字段，意味着同一数据集下相同推荐文本会被合并；但不同数据集下相同 recommend 会各自保留。

5. **潜在风险点**：
   - `agent` 可能为 `null`（`agentId` 缺失或无效），`agent.enableSearch()` 会 NPE，缺少空判断。
   - `getPossibleDataSets` 在 `dataSetIds` 非空时直接返回其副本，分支后的 `NatureHelper.selectPossibleDataSets` 与上下文回退逻辑实际很少触发。
   - `modelIdToDataSetIds` 使用 `User.getDefaultUser()` 获取，未用请求用户，可能存在多租户下数据集映射可见性不一致的隐患（取决于 `dataSetService` 内部实现）。
   - `RESULT_SIZE=10` 硬编码，不可配置。
   - `searchDimensionValue` 中 `getFilteredMetrics` 形参名为 `modelId`，实参传 `dataSetId`，命名易误导（逻辑上 `SchemaElement.getDataSetId()` 即数据集维度，语义正确）。

6. **与 parse 的差异**：`search` 不创建 `ChatQueryDO`、不落库、不调用 LLM、不产生 `S2SQL`，是纯检索/推荐能力，典型用于输入框联想、查询改写建议。
