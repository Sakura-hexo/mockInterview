package com.sakura.interview.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sakura.interview.infra.EnvKeys;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 题库存储：稠密加稀疏双路 schema，BM25 函数由服务端从 search_text
 * 生成稀疏向量（入库只写文本），检索经 RRF 融合两路排名。
 * 入库按题号幂等；换 embedding 模型维度会变，必须 rebuild。
 */
public final class MilvusQuestionStore implements AutoCloseable, QuestionSearcher {

    private static final int RRF_K = 60;
    private static final Duration EMBED_TIMEOUT = Duration.ofSeconds(60);

    private static final String F_ID = "id";
    private static final String F_QUESTION = "question";
    private static final String F_POINTS = "points";
    private static final String F_TAGS = "tags";
    private static final String F_SOURCE = "source";
    private static final String F_SEARCH_TEXT = "search_text";
    private static final String F_SPARSE = "sparse";
    private static final String F_DENSE = "dense";

    private final MilvusClientV2 client;
    private final EmbeddingModel embeddingModel;
    private final String collection;
    private final int dimension;

    public MilvusQuestionStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.collection = EnvKeys.milvusCollection();
        this.dimension = embeddingModel.getDimensions();
        this.client = new MilvusClientV2(ConnectConfig.builder().uri(EnvKeys.milvusUri()).build());
    }

    public String embeddingName() {
        return embeddingModel.getModelName();
    }

    public int dimension() {
        return dimension;
    }

    public void rebuild() {
        if (hasCollection()) {
            client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        }
        createCollection();
    }

    public void ensureCollection() {
        if (!hasCollection()) {
            createCollection();
        }
    }

    /** 幂等入库：已存在题号跳过，返回本次实际入库数。 */
    public int importQuestions(List<BankQuestion> questions) {
        Set<String> existing = existingIds(questions.stream().map(BankQuestion::id).toList());
        List<JsonObject> rows = new ArrayList<>();
        for (BankQuestion q : questions) {
            if (existing.contains(q.id())) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty(F_ID, q.id());
            row.addProperty(F_QUESTION, q.question());
            row.addProperty(F_POINTS, q.points());
            row.addProperty(F_TAGS, q.tags());
            row.addProperty(F_SOURCE, q.source());
            row.addProperty(F_SEARCH_TEXT, q.searchText());
            row.add(F_DENSE, toJsonArray(embed(q.searchText())));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
        }
        return rows.size();
    }

    /** 检索端口实现：应用层与工具层经 QuestionSearcher 调用，内部走混合检索。 */
    @Override
    public List<SearchHit> search(String query, int topK) {
        return hybridSearch(query, topK);
    }

    /** 混合检索：稠密 COSINE 加稀疏 BM25，RRF 融合。 */
    public List<SearchHit> hybridSearch(String query, int topK) {
        SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                .collectionName(collection)
                .searchRequests(List.of(denseRequest(query, topK), sparseRequest(query, topK)))
                .ranker(new RRFRanker(RRF_K))
                .topK(topK)
                .outFields(List.of(F_ID, F_QUESTION, F_TAGS, F_SOURCE))
                .build());
        return toHits(resp);
    }

    /** 纯稠密检索，供与混合检索对比稀疏路的增益。 */
    public List<SearchHit> denseSearch(String query, int topK) {
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(collection)
                .annsField(F_DENSE)
                .data(List.of(new FloatVec(embed(query))))
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .outputFields(List.of(F_ID, F_QUESTION, F_TAGS, F_SOURCE))
                .build());
        return toHits(resp);
    }

    /** 纯稀疏 BM25 检索，供诊断中文分词与稀疏路的独立表现。 */
    public List<SearchHit> sparseSearch(String query, int topK) {
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(collection)
                .annsField(F_SPARSE)
                .data(List.of(new EmbeddedText(query)))
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .outputFields(List.of(F_ID, F_QUESTION, F_TAGS, F_SOURCE))
                .build());
        return toHits(resp);
    }

    @Override
    public void close() {
        client.close();
    }

    private AnnSearchReq denseRequest(String query, int topK) {
        return AnnSearchReq.builder()
                .vectorFieldName(F_DENSE)
                .vectors(List.of(new FloatVec(embed(query))))
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .build();
    }

    private AnnSearchReq sparseRequest(String query, int topK) {
        return AnnSearchReq.builder()
                .vectorFieldName(F_SPARSE)
                .vectors(List.of(new EmbeddedText(query)))
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .build();
    }

    private boolean hasCollection() {
        return Boolean.TRUE.equals(client.hasCollection(
                HasCollectionReq.builder().collectionName(collection).build()));
    }

    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build()
                .addField(AddFieldReq.builder().fieldName(F_ID).dataType(DataType.VarChar)
                        .maxLength(32).isPrimaryKey(true).build())
                .addField(AddFieldReq.builder().fieldName(F_QUESTION).dataType(DataType.VarChar)
                        .maxLength(2048).build())
                .addField(AddFieldReq.builder().fieldName(F_POINTS).dataType(DataType.VarChar)
                        .maxLength(4096).build())
                .addField(AddFieldReq.builder().fieldName(F_TAGS).dataType(DataType.VarChar)
                        .maxLength(256).build())
                .addField(AddFieldReq.builder().fieldName(F_SOURCE).dataType(DataType.VarChar)
                        .maxLength(128).build())
                .addField(AddFieldReq.builder().fieldName(F_SEARCH_TEXT).dataType(DataType.VarChar)
                        .maxLength(8192).enableAnalyzer(true)
                        .analyzerParams(Map.<String, Object>of("type", "chinese")).build())
                .addField(AddFieldReq.builder().fieldName(F_SPARSE)
                        .dataType(DataType.SparseFloatVector).build())
                .addField(AddFieldReq.builder().fieldName(F_DENSE).dataType(DataType.FloatVector)
                        .dimension(dimension).build());
        schema.addFunction(bm25Function());
        IndexParam denseIndex = IndexParam.builder().fieldName(F_DENSE)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE).build();
        IndexParam sparseIndex = IndexParam.builder().fieldName(F_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25).build();
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema)
                .indexParams(List.of(denseIndex, sparseIndex))
                .build());
    }

    private CreateCollectionReq.Function bm25Function() {
        return CreateCollectionReq.Function.builder()
                .name("search_text_bm25")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of(F_SEARCH_TEXT))
                .outputFieldNames(List.of(F_SPARSE))
                .build();
    }

    private Set<String> existingIds(List<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        QueryResp resp = client.query(QueryReq.builder()
                .collectionName(collection)
                .ids(new ArrayList<>(ids))
                .outputFields(List.of(F_ID))
                .build());
        Set<String> existing = new HashSet<>();
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            existing.add(String.valueOf(result.getEntity().get(F_ID)));
        }
        return existing;
    }

    private float[] embed(String text) {
        Mono<double[]> mono = embeddingModel.embed(TextBlock.builder().text(text).build());
        double[] vector = mono.block(EMBED_TIMEOUT);
        if (vector == null) {
            throw new IllegalStateException("embedding 无返回：" + text);
        }
        if (vector.length != dimension) {
            throw new IllegalStateException("embedding 维度不符：期望 " + dimension
                    + " 实际 " + vector.length + "，换模型请用 --rebuild 重建题库");
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) vector[i];
        }
        return result;
    }

    private static JsonArray toJsonArray(float[] values) {
        JsonArray array = new JsonArray(values.length);
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<SearchHit> toHits(SearchResp resp) {
        List<SearchHit> hits = new ArrayList<>();
        for (List<SearchResp.SearchResult> round : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : round) {
                Map<String, Object> entity = result.getEntity();
                hits.add(new SearchHit(
                        String.valueOf(entity.get(F_ID)),
                        String.valueOf(entity.get(F_QUESTION)),
                        String.valueOf(entity.get(F_TAGS)),
                        String.valueOf(entity.get(F_SOURCE)),
                        result.getScore() == null ? 0f : result.getScore()));
            }
        }
        return hits;
    }
}
