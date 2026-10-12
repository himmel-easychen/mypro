package com.workpro.rag;

import com.workpro.persistence.RagPersistence;
import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.ProviderScore;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;
import com.workpro.rag.RetrievalContracts.RetrievalProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL-backed retrieval over parsed table cells and their source chunks. */
@Service
public class StructuredRetrievalProvider implements RetrievalProvider {
    public static final int PROVIDER_TOP_K = 20;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final List<String> STOP_WORDS = List.of(
            "请问", "如何", "是否", "哪些", "哪个", "多少", "什么", "怎么", "以及", "对应", "项目", "本次", "的", "是", "有",
            "如果", "申报", "维修", "企业", "人员", "配置", "要求", "分别", "最低", "一类", "二类", "需要", "至少",
            "可以", "必须", "这套", "申请", "资质", "所需", "检测", "设备", "在检", "时需", "汽车", "分别", "多少"
    );

    private final RagPersistence persistence;

    public StructuredRetrievalProvider(RagPersistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public List<RetrievalCandidate> search(String query, String documentId, Map<String, Object> filters) {
        List<String> terms = extractTerms(query);
        List<RagPersistence.VectorHit> hits = persistence.searchByStructured(
                terms.toArray(String[]::new), VectorRetrievalProvider.requestedTopK(filters, PROVIDER_TOP_K), documentId, filters);
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> toCandidate(hits.get(index), index + 1))
                .toList();
    }

    static List<String> extractTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.replaceAll("[，。！？；：、（）()【】\\[\\]\\\"'‘’“”]", " ");
        Matcher matcher = TOKEN.matcher(normalized);
        List<String> terms = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 2 || STOP_WORDS.contains(token)) continue;
            terms.add(token);
        }
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                han.append(ch);
            } else {
                addHanBigrams(han, terms);
                han.setLength(0);
            }
        }
        addHanBigrams(han, terms);
        return terms.stream().distinct().limit(24).toList();
    }

    private static void addHanBigrams(StringBuilder text, List<String> terms) {
        for (int i = 0; i + 1 < text.length(); i++) {
            String token = text.substring(i, i + 2);
            if (!STOP_WORDS.contains(token)) terms.add(token);
        }
    }

    private RetrievalCandidate toCandidate(RagPersistence.VectorHit hit, int rank) {
        return new RetrievalCandidate(
                hit.chunkId(), hit.parentChunkId(), hit.documentId(), hit.sourceFile(), hit.content(), hit.location(),
                Map.of("STRUCTURED", new ProviderScore(rank, hit.similarity())), hit.similarity(), CandidateStatus.RETRIEVED);
    }
}
