package com.workpro.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import com.workpro.rag.StructuredDocument;
@Service public class RagPersistence {
 public static final String VECTOR_SEARCH_SQL = "select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum,c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type,1-(ce.embedding <=> ?::vector) as similarity from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id join chunk_embedding ce on ce.chunk_id=c.id where c.chunk_role='child' and ce.embedding is not null order by ce.embedding <=> ?::vector limit ?";
 public static final String KEYWORD_SEARCH_SQL = "select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum,c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type,ts_rank(to_tsvector('simple', coalesce(c.content,'')), websearch_to_tsquery('simple', ?)) as similarity from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id where c.chunk_role='child' and to_tsvector('simple', coalesce(c.content,'')) @@ websearch_to_tsquery('simple', ?) and d.document_id = coalesce(?::uuid, d.document_id) order by similarity desc, c.id limit ?";
 private final JdbcTemplate jdbc;
 private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
 public RagPersistence(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void recordDocument(String docId, String sourceFile, String sourceType, String quality,
                            List<Chunk> chunks, int dimension, String checksum) {
  recordDocument(docId, sourceFile, sourceType, quality, chunks, dimension, checksum,
    java.util.Map.of(), "mock-embedding", "memory");
 }
 public void recordDocument(String docId, String sourceFile, String sourceType, String quality,
                            List<Chunk> chunks, int dimension, String checksum,
                            java.util.Map<String, double[]> vectors, String model, String provider) {
  recordDocument(docId, sourceFile, sourceType, documentStatus("accepted", true), quality,
    chunks, dimension, checksum, vectors, model, provider);
 }
 public void recordDocument(String docId, String sourceFile, String sourceType, String status,
                            String quality, List<Chunk> chunks, int dimension, String checksum,
                            java.util.Map<String, double[]> vectors, String model, String provider) {
  Long ws=jdbc.queryForObject("select id from workspace where code='default'",Long.class);
  jdbc.update("insert into document(document_id,workspace_id,source_file,source_type,status,quality,chunk_count,checksum) values(?,?,?,?,?,?,?,?)",
    UUID.fromString(docId), ws, sourceFile, sourceType, status, quality, chunks.size(), checksum);
  Long doc=jdbc.queryForObject("select id from document where document_id=?",Long.class,UUID.fromString(docId)); Long collection=ensureCollection(ws,dimension,model,provider);
  Map<String, Long> dbChunkIds = new java.util.HashMap<>();
  chunks.stream().filter(c -> "parent".equals(c.role())).forEach(c -> {
   jdbc.update("insert into document_chunk(chunk_id,document_id,parent_chunk_id,chunk_role,block_type,content,location,source_anchor,confidence,evidence_type) values(?,?,?,?,?,?,?,?,?,?)",UUID.fromString(c.id()),doc,null,c.role(),c.blockType(),c.content(),c.location(),c.location(),c.confidence(),c.evidenceType());
   dbChunkIds.put(c.id(), jdbc.queryForObject("select id from document_chunk where chunk_id=?", Long.class, UUID.fromString(c.id())));
  });
  chunks.stream().filter(c -> !"parent".equals(c.role())).forEach(c->{
   Long parentId = c.parentChunkId() == null ? null : dbChunkIds.get(c.parentChunkId());
   jdbc.update("insert into document_chunk(chunk_id,document_id,parent_chunk_id,chunk_role,block_type,content,location,source_anchor,confidence,evidence_type) values(?,?,?,?,?,?,?,?,?,?)",UUID.fromString(c.id()),doc,parentId,c.role(),c.blockType(),c.content(),c.location(),c.location(),c.confidence(),c.evidenceType());
   Long cid = jdbc.queryForObject(
     "select id from document_chunk where chunk_id=?",
     Long.class,
     UUID.fromString(c.id()));
   dbChunkIds.put(c.id(), cid);
   String ext = "emb:" + c.id();
   String vectorJson = toJson(vectors.get(c.id()));
   jdbc.update(
     "insert into chunk_embedding(chunk_id,collection_id,model,dimension,external_id,status,embedding_json,embedding) " +
       "values(?,?,?,?,?,'ready',?::jsonb,?::vector) " +
       "on conflict(chunk_id,collection_id,model) do update set " +
       "embedding_json=excluded.embedding_json,embedding=excluded.embedding,status='ready',updated_at=now()",
     cid,
     collection,
     model,
     dimension,
     ext,
     vectorJson,
     vectorJson);
   jdbc.update(
     "insert into index_ref(chunk_id,index_type,provider,collection_name,external_id,index_status) " +
       "values(?,?,?,?,?,'ready') on conflict do nothing",
     cid,
     "embedding",
     provider,
     "default",
     ext);
  });
 }
 public static String documentStatus(String qualityStatus, boolean pagePasses) {
  return "accepted".equalsIgnoreCase(Objects.requireNonNullElse(qualityStatus, "")) && pagePasses
    ? "ready" : "needs_review";
 }
 private String toJson(double[] vector) {
  try { return vector == null ? null : objectMapper.writeValueAsString(vector); }
  catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("E0005 向量序列化失败", exception); }
 }
 private String toJson(Object value) {
  try { return value == null ? null : objectMapper.writeValueAsString(value); }
  catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("E0005 JSON 序列化失败", exception); }
 }
 public Optional<ExistingDocument> findDocumentByChecksum(String checksum) {
  List<ExistingDocument> matches = jdbc.query(
    "select d.document_id,count(su.id) from document d left join source_unit su on su.document_id=d.id where d.workspace_id=(select id from workspace where code='default') and d.checksum=? group by d.document_id",
    (result, row) -> new ExistingDocument(result.getObject(1, UUID.class).toString(), result.getInt(2)),
    checksum);
  return matches.stream().findFirst();
 }
 public void recordStructured(String docId, StructuredDocument structured) {
  recordStructured(docId, structured, "accepted");
 }
 public void recordStructured(String docId, StructuredDocument structured, String qualityStatus) {
  Long document = jdbc.queryForObject("select id from document where document_id=?", Long.class, UUID.fromString(docId));
  if (document == null) return;
  java.util.Map<String, Long> units = new java.util.HashMap<>();
  java.util.Map<String, String> unitTexts = new java.util.HashMap<>();
  for (StructuredDocument.Block block : structured.blocks()) {
   String previous = unitTexts.get(block.sourceUnitId());
   unitTexts.put(block.sourceUnitId(), previous == null || previous.isBlank()
     ? block.rawText() : previous + "\n" + block.rawText());
  }
  for (StructuredDocument.SourceUnit unit : structured.sourceUnits()) {
   jdbc.update("insert into source_unit(source_unit_id,document_id,unit_type,page_number,logical_key,sheet_name,raw_text,normalized_text) values(?,?,?,?,?,?,?,?) on conflict(source_unit_id) do nothing",
     UUID.fromString(unit.id()), document, unit.pageType(), unit.pageNumber(), unit.logicalKey(), unit.sheetName(),
     unitTexts.get(unit.id()), unitTexts.get(unit.id()));
   Long id = jdbc.queryForObject("select id from source_unit where source_unit_id=?", Long.class, UUID.fromString(unit.id()));
   units.put(unit.id(), id);
   jdbc.update("insert into source_unit_quality(source_unit_id,status,retry_count) values(?,?,0) on conflict(source_unit_id) do update set status=excluded.status,updated_at=now()", id, qualityStatus);
  }
  for (StructuredDocument.Block block : structured.blocks()) {
   Long sourceUnit = units.get(block.sourceUnitId());
   if (sourceUnit == null) continue;
   String metadata = toJson(block.metadata());
   Object bbox = block.metadata().get("bbox");
   Object confidence = block.metadata().get("confidence");
   jdbc.update("insert into document_block(block_id,document_id,source_unit_id,block_type,order_index,raw_text,normalized_text,bbox_json,confidence,metadata_json) values(?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) on conflict(block_id) do nothing",
     UUID.fromString(block.id()), document, sourceUnit, block.blockType(), block.orderIndex(), block.rawText(), block.normalizedText(),
     bbox == null ? null : toJson(bbox), confidence instanceof Number n ? n.doubleValue() : null, metadata);
  }
  for (StructuredDocument.TableData table : structured.tables()) {
   Long sourceUnit = units.get(structured.sourceUnits().stream().filter(u -> u.pageNumber() == table.pageNumber() && Objects.equals(u.sheetName(), table.sheetName())).map(StructuredDocument.SourceUnit::id).findFirst().orElse(null));
   if (sourceUnit == null) continue;
   jdbc.update("insert into document_table(table_id,document_id,source_unit_id,table_index,sheet_name,headers_json) values(?,?,?,?,?,to_jsonb(?::text)) on conflict(table_id) do nothing",
     UUID.fromString(table.id()), document, sourceUnit, 0, table.sheetName(), new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(table.headers()).toString());
   Long tableId = jdbc.queryForObject("select id from document_table where table_id=?", Long.class, UUID.fromString(table.id()));
   for (StructuredDocument.TableCell cell : table.cells()) {
    jdbc.update("insert into document_table_cell(table_id,row_index,column_index,column_name,raw_text,normalized_text,unit) values(?,?,?,?,?,?,?) on conflict(table_id,row_index,column_index) do nothing",
      tableId, cell.rowIndex(), cell.columnIndex(), cell.columnName(), cell.rawText(), cell.normalizedText(), cell.unit());
   }
  }
 }
 public void recordFailedPages(String docId, List<Integer> pageNumbers, String failureReason) {
  if (pageNumbers == null || pageNumbers.isEmpty()) return;
  Long document = jdbc.queryForObject(
    "select id from document where document_id=?",
    Long.class,
    UUID.fromString(docId));
  for (Integer pageNumber : pageNumbers) {
   jdbc.update(
     "insert into document_failed_page(document_id,page_number,status,retry_count,failure_reason) " +
       "values(?,?,'failed',1,?) on conflict(document_id,page_number) do update set " +
       "status=excluded.status,retry_count=excluded.retry_count," +
       "failure_reason=excluded.failure_reason,updated_at=now()",
     document,
     pageNumber,
     failureReason);
  }
 }
 private Long ensureCollection(Long ws, int dimension, String model, String provider) {
  jdbc.update("insert into embedding_collection(collection_id,workspace_id,name,provider,model,dimension) values(?,?,?,?,?,?) on conflict(workspace_id,name,model) do nothing",
    UUID.randomUUID(), ws, "default", provider, model, dimension);
  return jdbc.queryForObject("select id from embedding_collection where workspace_id=? and name='default' and model=?",
    Long.class, ws, model);
 }
 public List<PersistedChunk> loadChunks() {
  return jdbc.query("select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum,c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type,coalesce(ce.embedding::text,ce.embedding_json::text) as embedding_value from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id left join chunk_embedding ce on ce.chunk_id=c.id order by d.created_at,c.id",
    (result, row) -> new PersistedChunk(
      new Chunk(result.getObject("chunk_id", UUID.class).toString(), result.getString("chunk_role"),
        result.getString("block_type"), result.getString("content"), result.getString("location"),
        result.getDouble("confidence"), result.getString("evidence_type"), result.getString("parent_chunk_id")),
      result.getObject("document_id", UUID.class).toString(), result.getString("source_file"),
      result.getString("source_type"), result.getString("checksum"), parseVector(result.getString("embedding_value"))));
 }
 public List<IndexChunk> loadChunksForSearchIndex() {
  if (jdbc == null) return List.of();
  return jdbc.query("select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum,c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type " +
    "from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id " +
    "where c.chunk_role='child' and c.content is not null and btrim(c.content)<>'' order by d.created_at,c.id",
    (rs, row) -> new IndexChunk(rs.getString("chunk_id"), rs.getString("parent_chunk_id"),
      rs.getString("document_id"), rs.getString("source_file"), rs.getString("source_type"),
      rs.getString("checksum"), rs.getString("chunk_role"), rs.getString("block_type"),
      rs.getString("content"), rs.getString("location"), rs.getString("source_anchor"),
      rs.getDouble("confidence"), rs.getString("evidence_type")));
 }
 public List<IndexChunk> loadChunksByDocumentIds(List<String> documentIds, int limit) {
  if (jdbc == null || documentIds == null || documentIds.isEmpty() || limit <= 0) return List.of();
  List<UUID> ids = documentIds.stream().filter(Objects::nonNull).filter(value -> !value.isBlank())
    .map(UUID::fromString).distinct().toList();
  if (ids.isEmpty()) return List.of();
  String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?::uuid"));
  List<Object> args = new java.util.ArrayList<>(ids);
  args.add(limit);
  return jdbc.query("select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum," +
      "c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type " +
      "from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id " +
      "where c.chunk_role='child' and c.content is not null and btrim(c.content)<>'' and d.document_id in (" + placeholders + ") " +
      "order by d.created_at,c.id limit ?",
    (rs, row) -> new IndexChunk(rs.getString("chunk_id"), rs.getString("parent_chunk_id"),
      rs.getString("document_id"), rs.getString("source_file"), rs.getString("source_type"),
      rs.getString("checksum"), rs.getString("chunk_role"), rs.getString("block_type"),
      rs.getString("content"), rs.getString("location"), rs.getString("source_anchor"),
      rs.getDouble("confidence"), rs.getString("evidence_type")), args.toArray());
 }
 public List<VectorHit> searchByVector(double[] queryVector, int limit, String documentId) {
  return searchByVector(queryVector, limit, documentId, null, 0);
 }
 public List<VectorHit> searchByVector(double[] queryVector, int limit, String documentId,
                                       String model, int dimension) {
  if (queryVector == null || queryVector.length == 0 || jdbc == null || limit <= 0) return List.of();
  String sql = VECTOR_SEARCH_SQL;
  StringBuilder predicates = new StringBuilder();
  if (model != null && !model.isBlank()) predicates.append(" and ce.model=? and ce.dimension=?");
  if (documentId != null && !documentId.isBlank()) {
   predicates.append(" and d.document_id=?::uuid");
  }
  sql = sql.replace("where c.chunk_role='child' and ce.embedding is not null",
    "where c.chunk_role='child' and ce.embedding is not null" + predicates);
  String literal = pgVectorLiteral(queryVector);
  List<Object> args = new java.util.ArrayList<>();
  args.add(literal);
  if (model != null && !model.isBlank()) { args.add(model); args.add(dimension); }
  if (documentId != null && !documentId.isBlank()) args.add(documentId);
  args.add(literal);
  args.add(limit);
  return jdbc.query(sql, (rs, row) -> vectorHit(rs), args.toArray());
 }
 public List<VectorHit> searchByKeyword(String query, int limit, String documentId) {
  if (query == null || query.isBlank() || jdbc == null || limit <= 0) return List.of();
  return jdbc.query(KEYWORD_SEARCH_SQL, (rs, row) -> vectorHit(rs), query, query,
    documentId == null || documentId.isBlank() ? null : documentId, limit);
 }
 public List<VectorHit> searchByExact(String query, int limit, String documentId, Map<String, Object> filters) {
  if (query == null || query.isBlank() || jdbc == null || limit <= 0) return List.of();
  StringBuilder sql = new StringBuilder(
    "select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum," +
      "c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type,1.0 as similarity " +
      "from document_chunk c left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id " +
      "where c.chunk_role='child' and c.content ilike '%' || ? || '%' ");
  List<Object> args = new java.util.ArrayList<>();
  args.add(query);
  if (documentId != null && !documentId.isBlank()) {
   sql.append("and d.document_id=?::uuid ");
   args.add(documentId);
  }
  Object location = filters == null ? null : filters.get("location");
  if (location != null && !location.toString().isBlank()) {
   sql.append("and c.location=? ");
   args.add(location.toString());
  }
  sql.append("order by c.id limit ?");
  args.add(limit);
  return jdbc.query(sql.toString(), (rs, row) -> vectorHit(rs), args.toArray());
 }
 public List<VectorHit> searchByStructured(String[] terms, int limit, String documentId, Map<String, Object> filters) {
  if (terms == null || terms.length == 0 || jdbc == null || limit <= 0) return List.of();
  java.util.List<String> usable = java.util.Arrays.stream(terms)
    .filter(t -> t != null && !t.isBlank()).distinct().limit(24).toList();
  if (usable.isEmpty()) return List.of();
  StringBuilder unitScore = new StringBuilder();
  StringBuilder unitWhere = new StringBuilder();
  StringBuilder cellScore = new StringBuilder();
  StringBuilder cellWhere = new StringBuilder();
  StringBuilder chunkScore = new StringBuilder();
  java.util.List<Object> args = new java.util.ArrayList<>();
  for (String term : usable) {
   if (unitScore.length() > 0) unitScore.append(" + ");
   unitScore.append("case when lower(concat_ws(' ',coalesce(su.logical_key,''),coalesce(su.sheet_name,''),coalesce(su.raw_text,''),coalesce(su.normalized_text,''))) like ? then 2 else 0 end");
   args.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
  }
  for (String term : usable) {
   if (unitWhere.length() > 0) unitWhere.append(" or ");
   unitWhere.append("lower(concat_ws(' ',coalesce(su.logical_key,''),coalesce(su.sheet_name,''),coalesce(su.raw_text,''),coalesce(su.normalized_text,''))) like ?");
   args.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
  }
  for (String term : usable) {
   if (cellScore.length() > 0) cellScore.append(" + ");
   cellScore.append("count(distinct tc.id) filter (where lower(concat_ws(' ',coalesce(tc.column_name,''),coalesce(tc.raw_text,''),coalesce(tc.normalized_text,''),coalesce(tc.unit,''))) like ?)");
   args.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
  }
  for (String term : usable) {
   if (cellWhere.length() > 0) cellWhere.append(" or ");
   cellWhere.append("lower(concat_ws(' ',coalesce(tc.column_name,''),coalesce(tc.raw_text,''),coalesce(tc.normalized_text,''),coalesce(tc.unit,''))) like ?");
   args.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
  }
  for (String term : usable) {
   if (chunkScore.length() > 0) chunkScore.append(" + ");
   chunkScore.append("case when lower(concat_ws(' ',coalesce(c.block_type,''),coalesce(c.content,''),coalesce(c.location,''),coalesce(c.source_anchor,''),coalesce(c.evidence_type,''))) like ? then 3 else 0 end");
   args.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
  }
  StringBuilder sql = new StringBuilder("with unit_hits as ("
    + "select su.id as source_unit_id,su.document_id,su.page_number,(" + unitScore + ")::double precision as score "
    + "from source_unit su where (" + unitWhere + ") "
    + "union all "
    + "select su.id as source_unit_id,su.document_id,su.page_number,(" + cellScore + ")::double precision as score "
    + "from source_unit su join document_table t on t.source_unit_id=su.id join document_table_cell tc on tc.table_id=t.id "
    + "where (" + cellWhere + ") group by su.id,su.document_id,su.page_number), "
    + "matched_units as (select source_unit_id,document_id,page_number,sum(score) as unit_score from unit_hits "
    + "group by source_unit_id,document_id,page_number order by sum(score) desc limit 80) "
    + "select c.chunk_id,pc.chunk_id as parent_chunk_id,d.document_id,d.source_file,d.source_type,d.checksum,"
    + "c.chunk_role,c.block_type,c.content,c.location,c.source_anchor,c.confidence,c.evidence_type,(mu.unit_score + " + chunkScore + ")::double precision as similarity "
    + "from matched_units mu join document_chunk c on c.document_id=mu.document_id and c.chunk_role='child' and "
    + "(c.location=('page:' || mu.page_number) or c.location like ('page:' || mu.page_number || '#%')) "
    + "left join document_chunk pc on pc.id=c.parent_chunk_id join document d on d.id=c.document_id where 1=1 ");
  if (documentId != null && !documentId.isBlank()) {
   sql.append("and d.document_id=?::uuid ");
   args.add(documentId);
  }
  Object location = filters == null ? null : filters.get("location");
  if (location != null && !location.toString().isBlank()) {
   sql.append("and c.location=? ");
   args.add(location.toString());
  }
  sql.append("order by similarity desc,c.id limit ?");
  args.add(limit);
  return jdbc.query(sql.toString(), (rs, row) -> vectorHit(rs), args.toArray());
 }
 public EmbeddingAudit embeddingAudit() {
  return embeddingAudit("qwen3.7-text-embedding", 1024);
 }
 public EmbeddingAudit embeddingAudit(String model, int dimension) {
  if (jdbc == null) return new EmbeddingAudit(0, 0, 0, 0, 0);
  Map<String, Object> row = jdbc.queryForMap(
    "select count(*) as total, " +
      "(select count(distinct chunk_id) from chunk_embedding where embedding is not null) as native_vectors, " +
      "count(*) filter (where exists (select 1 from chunk_embedding ce where ce.chunk_id=c.id " +
        "and ce.model=? and ce.dimension=? and ce.embedding is not null)) as production_vectors, " +
      "count(*) filter (where not exists (select 1 from chunk_embedding ce where ce.chunk_id=c.id " +
        "and ce.model=? and ce.dimension=? and ce.embedding is not null) and exists " +
        "(select 1 from chunk_embedding ce where ce.chunk_id=c.id and ce.embedding is not null)) as legacy_vectors, " +
      "count(*) filter (where not exists (select 1 from chunk_embedding ce where ce.chunk_id=c.id " +
        "and ce.model=? and ce.dimension=? and ce.embedding is not null)) as reindex_required " +
      "from document_chunk c where c.content is not null and btrim(c.content)<>''",
    model, dimension, model, dimension, model, dimension);
  return EmbeddingAudit.from(row);
 }
 public List<ReindexChunk> loadChunksForReindex(String model, int dimension, int limit) {
  if (jdbc == null) return List.of();
  String sql = "select c.chunk_id,d.document_id,c.content from document_chunk c join document d on d.id=c.document_id " +
    "where c.content is not null and btrim(c.content)<>'' and not exists (" +
    "select 1 from chunk_embedding ce where ce.chunk_id=c.id and ce.model=? and ce.dimension=? and ce.embedding is not null) " +
    "order by d.created_at,c.id" + (limit > 0 ? " limit ?" : "");
  if (limit > 0) {
   return jdbc.query(sql, (rs, row) -> new ReindexChunk(rs.getObject("chunk_id", UUID.class).toString(),
     rs.getObject("document_id", UUID.class).toString(), rs.getString("content")), model, dimension, limit);
  }
  return jdbc.query(sql, (rs, row) -> new ReindexChunk(rs.getObject("chunk_id", UUID.class).toString(),
    rs.getObject("document_id", UUID.class).toString(), rs.getString("content")), model, dimension);
 }
 public void upsertEmbedding(String chunkId, double[] vector, String model, int dimension, String provider) {
  if (jdbc == null) throw new IllegalStateException("数据库连接不可用");
  if (vector == null || vector.length != dimension) throw new IllegalArgumentException("embedding 维度不匹配");
  Long workspace = jdbc.queryForObject("select d.workspace_id from document_chunk c join document d on d.id=c.document_id where c.chunk_id=?", Long.class, UUID.fromString(chunkId));
  Long chunk = jdbc.queryForObject("select id from document_chunk where chunk_id=?", Long.class, UUID.fromString(chunkId));
  Long collection = ensureCollection(workspace, dimension, model, provider);
  String json = toJson(vector);
  jdbc.update("insert into chunk_embedding(chunk_id,collection_id,model,dimension,external_id,status,embedding_json,embedding) values(?,?,?,?,?,'ready',?::jsonb,?::vector) " +
    "on conflict(chunk_id,collection_id,model) do update set dimension=excluded.dimension,embedding_json=excluded.embedding_json,embedding=excluded.embedding,status='ready',updated_at=now()",
    chunk, collection, model, dimension, "emb:" + chunkId, json, json);
  jdbc.update("insert into index_ref(chunk_id,index_type,provider,collection_name,external_id,index_status) values(?,?,?,?,?,'ready') on conflict do nothing",
    chunk, "embedding", provider, "default", "emb:" + chunkId);
 }
 private long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
 private VectorHit vectorHit(java.sql.ResultSet rs) throws java.sql.SQLException {
  return new VectorHit(rs.getString("chunk_id"), rs.getString("parent_chunk_id"), rs.getString("document_id"), rs.getString("source_file"),
    rs.getString("source_type"), rs.getString("content"), rs.getString("location"), rs.getString("source_anchor"),
    rs.getDouble("confidence"), rs.getString("evidence_type"), rs.getDouble("similarity"));
 }
 public static String pgVectorLiteral(double[] vector) {
  if (vector == null || vector.length == 0) return "[]";
  StringBuilder result = new StringBuilder("[");
  for (int i = 0; i < vector.length; i++) {
   if (i > 0) result.append(',');
   if (!Double.isFinite(vector[i])) throw new IllegalArgumentException("向量包含非有限数");
   result.append(Double.toString(vector[i]));
  }
  return result.append(']').toString();
 }
 private double[] parseVector(String value) {
  if (value == null || value.isBlank()) return null;
  try { return objectMapper.readValue(value, double[].class); }
  catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return null; }
 }
 public record Chunk(String id,String role,String blockType,String content,String location,double confidence,String evidenceType,String parentChunkId){}
 public record ExistingDocument(String documentId, int sourceUnits) {}
 public record PersistedChunk(Chunk chunk, String documentId, String sourceFile, String sourceType,
                              String checksum, double[] vector) {}
 public record IndexChunk(String chunkId, String parentChunkId, String documentId, String sourceFile,
                          String sourceType, String checksum, String chunkRole, String blockType,
                          String content, String location, String sourceAnchor, double confidence,
                          String evidenceType) {}
 public record VectorHit(String chunkId, String parentChunkId, String documentId, String sourceFile, String sourceType,
                         String content, String location, String sourceAnchor, double confidence,
                         String evidenceType, double similarity) {}
 public record EmbeddingAudit(long total, long nativeVectors, long productionVectors, long legacyVectors,
                              long reindexRequired) {
  public static EmbeddingAudit from(Map<String, Object> row) {
   return new EmbeddingAudit(numberValue(row.get("total")), numberValue(row.get("native_vectors")),
     numberValue(row.get("production_vectors")), numberValue(row.get("legacy_vectors")),
     numberValue(row.get("reindex_required")));
  }
  private static long numberValue(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
 }
 public record ReindexChunk(String chunkId, String documentId, String content) {}
}
