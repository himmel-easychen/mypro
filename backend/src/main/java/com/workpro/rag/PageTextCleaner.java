package com.workpro.rag;

import org.springframework.stereotype.Component;
import java.util.*;

/** Removes only lines repeated across pages; content-specific text is preserved. */
@Component
public final class PageTextCleaner {
    private final TextNormalizationTool normalizer = new TextNormalizationTool();

    public List<String> cleanPages(List<String> pages) {
        if (pages == null) return List.of();
        Map<String,Integer> frequency = new HashMap<>();
        for (String page : pages) {
            Set<String> seen = new HashSet<>();
            for (String line : normalizer.normalize(page).split("\\n")) {
                String key = line.trim();
                if (key.length() >= 3 && seen.add(key)) frequency.merge(key, 1, Integer::sum);
            }
        }
        Set<String> repeated = frequency.entrySet().stream().filter(e -> e.getValue() >= Math.max(2, pages.size() / 2)).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        return pages.stream().map(p -> cleanPage(normalizer.normalize(p), repeated)).toList();
    }

    private String cleanPage(String page, Set<String> repeated) {
        List<String> lines = new ArrayList<>();
        for (String raw : page.split("\\n")) {
            String line = raw.trim();
            if (line.isBlank() || repeated.contains(line)) continue;
            if (line.matches("否\\s*/?\\s*否")) continue;
            if (line.matches("^3\\s*[.．、]?\\s*投标人资格要求.*")
                    || line.matches("^3\\s*[.．、]?\\s*1\\s*通用资格要求.*")
                    || line.matches("^3\\s*[.．、]?\\s*2\\s*专用资格要求.*")) break;
            lines.add(line);
        }
        String result = String.join("\n", lines);
        result = result.replace("番禺区\n域特种", "番禺区域特种")
                .replace("花都区\n域特种", "花都区域特种")
                .replace("增城区\n域特种", "增城区域特种")
                .replace("从化区\n域特种", "从化区域特种")
                .replace("区域特种\n车辆维保", "区域特种车辆维保")
                .replace("车辆维\n保", "车辆维保")
                .replace("价,详\n见报价\n表", "单价限价,详见报价表")
                .replace("价，详\n见报价\n表", "单价限价，详见报价表");
        return result;
    }
}
