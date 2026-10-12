package com.workpro.api;
import com.workpro.model.ModelServices.Properties;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/models")
public class ModelHealthController {
 private final Properties p;
 public ModelHealthController(Properties p){this.p=p;}
 @GetMapping("/health") public Map<String,Object> health(){return Map.of("llm",check(p.getLlm(),false),"embedding",check(p.getEmbedding(),true));}
 private Map<String,Object> check(Properties.Endpoint e,boolean embedding){boolean configured="mock".equalsIgnoreCase(e.getProvider())||(e.getBaseUrl()!=null&&!e.getBaseUrl().isBlank());Map<String,Object> m=new LinkedHashMap<>();m.put("provider",e.getProvider());m.put("model",e.getModel());m.put("configured",configured);m.put("connect_timeout_ms",e.getConnectTimeoutMs());m.put("read_timeout_ms",e.getReadTimeoutMs());m.put("retries",e.getRetries());if(embedding)m.put("dimension",e.getDimension());m.put("status",configured?"ready":"missing_configuration");return m;}
}
