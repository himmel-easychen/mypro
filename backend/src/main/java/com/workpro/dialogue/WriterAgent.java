package com.workpro.dialogue;
import com.workpro.model.ModelGateway;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class WriterAgent { private final ModelGateway llm; public WriterAgent(ModelGateway llm){this.llm=llm;} public String write(String query,List<String> evidence){String prompt=Objects.requireNonNullElse(query,"");if(evidence!=null&&!evidence.isEmpty())prompt+="\n证据:\n"+String.join("\n- ",evidence);return llm.complete(prompt).content();} }
