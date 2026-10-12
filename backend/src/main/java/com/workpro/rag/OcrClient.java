package com.workpro.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Component
public class OcrClient {
 private final RestClient client; private final boolean enabled; private final long maxBytes;
 public OcrClient(@Value("${workpro.ocr.base-url}") String baseUrl,
                  @Value("${workpro.ocr.enabled:true}") boolean enabled,
                  @Value("${workpro.ocr.connect-timeout-ms:3000}") int connectMs,
                  @Value("${workpro.ocr.read-timeout-ms:120000}") int readMs,
                  @Value("${workpro.ocr.max-file-bytes:104857600}") long maxBytes) {
  var f=new JdkClientHttpRequestFactory(); f.setReadTimeout(Duration.ofMillis(readMs));
  this.client=RestClient.builder().baseUrl(baseUrl).requestFactory(f).build(); this.enabled=enabled; this.maxBytes=maxBytes;
 }
 public OcrDocumentResult recognize(byte[] bytes,String filename) {
  return recognize(bytes, filename, null);
 }
 public OcrDocumentResult recognizePage(byte[] bytes,String filename,int page) {
  return recognize(bytes, filename, page);
 }
 public OcrDocumentResult recognizePagePlain(byte[] bytes,String filename,int page) {
  return recognize(bytes, filename, page, "plain");
 }
 private OcrDocumentResult recognize(byte[] bytes,String filename,Integer page) {
  return recognize(bytes, filename, page, null);
 }
 private OcrDocumentResult recognize(byte[] bytes,String filename,Integer page,String mode) {
  if(!enabled) throw new IllegalStateException("R1004 OCR 服务未启用");
  if(bytes.length>maxBytes) throw new IllegalArgumentException("R1002 文件超过 OCR 大小限制");
  var body=new LinkedMultiValueMap<String,Object>();
  body.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});
  try {
   var request=client.post().uri(builder->{var u=builder.path("/v1/ocr");if(page!=null)u.queryParam("page",page);if(mode!=null)u.queryParam("mode",mode);return u.build();});
   return request.contentType(MediaType.MULTIPART_FORM_DATA)
     .accept(MediaType.parseMediaType("application/json;charset=UTF-8"))
     .body(body).retrieve().body(OcrDocumentResult.class);
  }
  catch(Exception e){ throw new IllegalStateException("R1004 OCR 服务调用失败",e); }
 }
}
