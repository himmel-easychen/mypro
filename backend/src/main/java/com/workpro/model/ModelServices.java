package com.workpro.model;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

public final class ModelServices {
 @Configuration @ConfigurationProperties(prefix="workpro.model") public static class Properties { private Endpoint llm=new Endpoint(),embedding=new Endpoint(); public Endpoint getLlm(){return llm;} public void setLlm(Endpoint v){llm=v;} public Endpoint getEmbedding(){return embedding;} public void setEmbedding(Endpoint v){embedding=v;} public static class Endpoint { private String provider="mock",baseUrl="",apiKey="",model="local",thinking="disabled"; private double temperature=.2; private int maxTokens=1024,dimension=64,connectTimeoutMs=3000,readTimeoutMs=30000,retries=2; public String getThinking(){return thinking;} public void setThinking(String v){thinking=v;} public String getProvider(){return provider;} public void setProvider(String v){provider=v;} public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;} public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;} public String getModel(){return model;} public void setModel(String v){model=v;} public double getTemperature(){return temperature;} public void setTemperature(double v){temperature=v;} public int getMaxTokens(){return maxTokens;} public void setMaxTokens(int v){maxTokens=v;} public int getDimension(){return dimension;} public void setDimension(int v){dimension=v;} public int getConnectTimeoutMs(){return connectTimeoutMs;} public void setConnectTimeoutMs(int v){connectTimeoutMs=v;} public int getReadTimeoutMs(){return readTimeoutMs;} public void setReadTimeoutMs(int v){readTimeoutMs=v;} public int getRetries(){return retries;} public void setRetries(int v){retries=v;} } }
 private static JsonNode post(Properties.Endpoint e,String suffix,Object body){if(e.getBaseUrl()==null||e.getBaseUrl().isBlank())throw new IllegalStateException("E0005 模型配置缺少 base-url");String u=e.getBaseUrl().replaceAll("/+$","")+suffix;var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(e.getConnectTimeoutMs());f.setReadTimeout(e.getReadTimeoutMs());var c=RestClient.builder().requestFactory(f).build();Exception last=null;for(int i=0;i<=Math.max(0,e.getRetries());i++){try{return c.post().uri(u).contentType(MediaType.APPLICATION_JSON).headers(h->{if(e.getApiKey()!=null&&!e.getApiKey().isBlank())h.setBearerAuth(e.getApiKey());}).body(body).retrieve().body(JsonNode.class);}catch(Exception x){last=x;if(i<e.getRetries())try{Thread.sleep(100L*(i+1));}catch(InterruptedException z){Thread.currentThread().interrupt();}}}throw new IllegalStateException("E0005 模型服务调用失败: "+last.getMessage(),last);}
 @Service public static class LlmClient {
  private final Properties p;
  public LlmClient(Properties p) { this.p = p; }
  public String complete(String prompt) {
   return complete(prompt, false);
  }
  public String completeRag(String prompt) {
   return complete(prompt, true);
  }
  private String complete(String prompt, boolean structuredRag) {
   var e = p.getLlm();
   if ("mock".equalsIgnoreCase(e.getProvider()))
    return "[本地模型回答] " + prompt.substring(0, Math.min(500, prompt.length()));
   if ("ark-responses".equalsIgnoreCase(e.getProvider())) {
    if (e.getApiKey() == null || e.getApiKey().isBlank())
     throw new IllegalStateException("E0005 RAG 生成模型缺少 API Key");
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("model", e.getModel());
    body.put("input", List.of(Map.of("role", "user", "content",
      List.of(Map.of("type", "input_text", "text", prompt)))));
    body.put("max_output_tokens", e.getMaxTokens());
    body.put("thinking", Map.of("type", e.getThinking()));
    if (structuredRag) {
     body.put("tools", List.of(Map.of(
       "type", "function", "name", "submit_answer",
       "description", "提交基于给定证据的最终回答与可答性判断",
       "parameters", Map.of(
         "type", "object",
         "properties", Map.of(
           "supported", Map.of("type", "boolean"),
           "answerability", Map.of("type", "string", "enum", List.of("FULL", "PARTIAL", "NONE")),
           "answer", Map.of("type", "string")),
         "required", List.of("supported", "answerability", "answer"),
         "additionalProperties", false))));
     body.put("tool_choice", Map.of("type", "function", "name", "submit_answer"));
    }
    JsonNode response = post(e, "/responses", body);
    if (response == null || !"completed".equals(response.path("status").asText()))
     throw new IllegalStateException("E0005 生成响应未完成");
    StringBuilder text = new StringBuilder();
    for (JsonNode item : response.path("output")) {
     if (structuredRag && "function_call".equals(item.path("type").asText())
       && "submit_answer".equals(item.path("name").asText())
       && item.path("arguments").isTextual())
      text.append(item.path("arguments").asText());
     if (!"message".equals(item.path("type").asText())
       || !"assistant".equals(item.path("role").asText())) continue;
     for (JsonNode part : item.path("content")) {
      if ("output_text".equals(part.path("type").asText()) && part.path("text").isTextual())
       text.append(part.path("text").asText());
     }
    }
    if (text.toString().isBlank()) throw new IllegalStateException("E0005 生成响应没有正文");
    return text.toString();
   }
   Map<String,Object> body = new LinkedHashMap<>();
   body.put("model", e.getModel());
   body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
   body.put("temperature", e.getTemperature());
   body.put("max_tokens", e.getMaxTokens());
   if (e.getModel() != null && e.getModel().toLowerCase(Locale.ROOT).startsWith("deepseek-"))
    body.put("thinking", Map.of("type", e.getThinking()));
   if (structuredRag) {
    Map<String,Object> function = Map.of(
      "name", "submit_answer",
      "description", "提交基于给定证据的最终回答与可答性判断",
      "parameters", Map.of(
        "type", "object",
        "properties", Map.of(
          "supported", Map.of("type", "boolean"),
          "answerability", Map.of("type", "string", "enum", List.of("FULL", "PARTIAL", "NONE")),
          "answer", Map.of("type", "string")),
        "required", List.of("supported", "answerability", "answer"),
        "additionalProperties", false));
    body.put("tools", List.of(Map.of("type", "function", "function", function)));
    body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", "submit_answer")));
   }
   JsonNode n = post(e, "/v1/chat/completions", body);
   String value = structuredRag && n != null
     ? n.at("/choices/0/message/tool_calls/0/function/arguments").asText(null)
     : null;
   if (value == null && n != null) value = n.at("/choices/0/message/content").asText(null);
   if (value == null) throw new IllegalStateException("E0005 模型响应格式错误");
   return value;
  }
 }
 @Service
 public static class EmbeddingClient {
  private final Properties p;

  public EmbeddingClient(Properties p) {
   this.p = p;
  }

  public int dimension() {
   return p.getEmbedding().getDimension();
  }

  public String model() {
   return p.getEmbedding().getModel();
  }

  public String provider() {
   return p.getEmbedding().getProvider();
  }

  /** Embed a batch of texts, using DashScope's documented 10-text v4 limit. */
  public List<double[]> embedBatch(List<String> texts) {
   if (texts == null || texts.isEmpty()) return List.of();
   var endpoint = p.getEmbedding();
   if (!"dashscope".equalsIgnoreCase(endpoint.getProvider())) {
    return texts.stream().map(this::embed).toList();
   }
   Map<String, Object> body = Map.of(
     "model", endpoint.getModel(),
     "input", Map.of("texts", texts),
     "parameters", Map.of("dimension", endpoint.getDimension(), "output_type", "dense"));
   JsonNode items = post(endpoint,
     "/services/embeddings/text-embedding/text-embedding", body).at("/output/embeddings");
   if (!items.isArray() || items.size() != texts.size()) {
    throw new IllegalStateException("E0005 embedding 批量响应数量不匹配");
   }
   double[][] vectors = new double[texts.size()][];
   for (JsonNode item : items) {
    int index = item.path("text_index").asInt(-1);
    if (index < 0 || index >= vectors.length || vectors[index] != null) {
     throw new IllegalStateException("E0005 embedding 批量响应索引错误");
    }
    vectors[index] = toVector(item.path("embedding"));
    if (vectors[index].length != endpoint.getDimension()) {
     throw new IllegalStateException("E0005 embedding 维度不匹配: expected="
       + endpoint.getDimension() + ", actual=" + vectors[index].length);
    }
   }
   if (Arrays.stream(vectors).anyMatch(Objects::isNull)) {
    throw new IllegalStateException("E0005 embedding 批量响应缺少结果");
   }
   return Arrays.asList(vectors);
  }

  public double[] embed(String text) {
   var endpoint = p.getEmbedding();
   double[] vector;
   if ("mock".equalsIgnoreCase(endpoint.getProvider())) {
    vector = mock(text, endpoint.getDimension());
   } else if ("dashscope".equalsIgnoreCase(endpoint.getProvider())) {
    Map<String, Object> body = Map.of(
      "model", endpoint.getModel(),
      "input", Map.of("texts", List.of(text)),
      "parameters", Map.of("dimension", endpoint.getDimension(), "output_type", "dense"));
    JsonNode embedding = post(endpoint,
      "/services/embeddings/text-embedding/text-embedding", body)
      .at("/output/embeddings/0/embedding");
    vector = toVector(embedding);
   } else {
    JsonNode embedding = post(endpoint, "/v1/embeddings",
      Map.of("model", endpoint.getModel(), "input", text,
        "dimensions", endpoint.getDimension()))
      .at("/data/0/embedding");
    vector = toVector(embedding);
   }
   if (vector.length != endpoint.getDimension()) {
    throw new IllegalStateException("E0005 embedding 维度不匹配: expected="
      + endpoint.getDimension() + ", actual=" + vector.length);
   }
   return vector;
  }

  private double[] toVector(JsonNode embedding) {
   if (!embedding.isArray()) {
    throw new IllegalStateException("E0005 embedding 响应格式错误");
   }
   double[] vector = new double[embedding.size()];
   for (int i = 0; i < vector.length; i++) vector[i] = embedding.get(i).asDouble();
   return vector;
  }

  private double[] mock(String text, int dimension) {
   double[] vector = new double[dimension];
   for (String token : Objects.requireNonNullElse(text, "").toLowerCase().split("")) {
    vector[Math.floorMod(token.hashCode(), vector.length)]++;
   }
   return vector;
  }
 }
}
