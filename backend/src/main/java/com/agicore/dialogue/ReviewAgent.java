package com.agicore.dialogue;
import org.springframework.stereotype.Service; import java.util.*;
@Service public class ReviewAgent { public ReviewResult review(String content){String value=Objects.requireNonNullElse(content,"");return new ReviewResult(value.isBlank()?"blocked":"success",value.isBlank()?"E0001":null,value.isBlank()?List.of("补充正文内容"):List.of(),value.isBlank()?"生成内容为空":"基础校验通过");} public record ReviewResult(String status,String code,List<String> issues,String message){} }
