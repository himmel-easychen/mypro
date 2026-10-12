package com.workpro.dialogue;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Computes only explicit, contiguous lower-bound table columns; never guesses missing cells. */
public final class EvidenceArithmetic {
    private static final Pattern LOWER_BOUND = Pattern.compile("(?:≥|>=|不少于)\\s*(\\d+(?:\\.\\d+)?)");
    private EvidenceArithmetic() {}

    public static List<Map<String, Object>> summarize(List<RagPromptBuilder.Evidence> evidence) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var e : evidence) {
            String text = e.parentContent().isBlank() ? e.content() : e.parentContent();
            if (!seen.add(text)) continue;
            List<String[]> rows = new ArrayList<>();
            String[] header = new String[0];
            for (String line : (text.replace("\\n", "\n") + "\nEND").split("\\R")) {
                String[] cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
                boolean numbered = cells.length >= 3 && cells[0].matches("\\d+");
                if (numbered && Integer.parseInt(cells[0]) == rows.size() + 1
                        && (rows.isEmpty() || cells.length == rows.get(0).length)) {
                    rows.add(cells);
                } else {
                    addColumns(result, e.id(), header, rows);
                    rows.clear();
                    if (numbered && cells[0].equals("1")) rows.add(cells);
                    else if (cells.length >= 3) header = cells;
                }
            }
        }
        return result;
    }

    private static void addColumns(List<Map<String, Object>> result, String id, String[] header, List<String[]> rows) {
        if (rows.size() < 2 || header.length != rows.get(0).length) return;
        for (int column = 2; column < header.length; column++) {
            if (header[column].isBlank()) continue;
            BigDecimal sum = BigDecimal.ZERO;
            List<Map<String, String>> operands = new ArrayList<>();
            for (String[] row : rows) {
                var match = LOWER_BOUND.matcher(row[column]);
                if (!match.matches()) break;
                sum = sum.add(new BigDecimal(match.group(1)));
                operands.add(Map.of("row", row[0], "label", row[1], "minimum", match.group(1)));
            }
            if (operands.size() == rows.size()) result.add(Map.of(
                    "evidenceId", id, "column", header[column], "operation", "sum_of_explicit_lower_bounds",
                    "rowCount", rows.size(), "operands", operands, "sum", sum.stripTrailingZeros().toPlainString(),
                    "scope", "仅合计所列行；不推断表外项目、兼职关系或人数"));
        }
    }
}
