package com.agicore.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a source unit from the strongest semantic boundary to weaker ones.
 * The original text is retained in order (including separators), while each
 * returned part is bounded by {@code maxChars} whenever possible.
 */
public final class RecursiveChunker {
    private static final Pattern HEADING = Pattern.compile(
            "^(?:第\\s*[一二三四五六七八九十百千万0-9]+\\s*[章节条]|"
                    + "[一二三四五六七八九十百千万0-9]+\\s*[、.．]|"
                    + "[（(]?[一二三四五六七八九十百千万0-9]+[）)、.．])\\s*.+$");
    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;])\\s*");
    private static final Pattern COMMA = Pattern.compile("(?<=[，,、])\\s*");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    public record ChunkPart(String content, String splitLevel) {
        public ChunkPart {
            content = content == null ? "" : content;
            splitLevel = splitLevel == null ? "character" : splitLevel;
        }
    }

    private enum Level {
        HEADING("heading"), PARAGRAPH("paragraph"), SENTENCE("sentence"),
        COMMA("comma"), SPACE("space"), CHARACTER("character");

        private final String label;

        Level(String label) {
            this.label = label;
        }
    }

    public List<ChunkPart> split(String text, int maxChars) {
        if (text == null || text.isBlank()) return List.of();
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        List<ChunkPart> result = new ArrayList<>();
        splitRecursive(text, maxChars, Level.HEADING, result);
        return List.copyOf(result);
    }

    private void splitRecursive(String text, int maxChars, Level level, List<ChunkPart> result) {
        String value = text == null ? "" : text;
        if (value.isBlank()) return;
        if (value.length() <= maxChars) {
            result.add(new ChunkPart(value, level.label));
            return;
        }
        if (level == Level.CHARACTER) {
            for (int start = 0; start < value.length(); start += maxChars) {
                result.add(new ChunkPart(value.substring(start, Math.min(start + maxChars, value.length())), level.label));
            }
            return;
        }

        List<String> pieces = splitAtLevel(value, level);
        if (pieces.size() <= 1) {
            splitRecursive(value, maxChars, next(level), result);
            return;
        }
        for (String piece : pieces) splitRecursive(piece, maxChars, next(level), result);
    }

    private Level next(Level level) {
        return Level.values()[Math.min(level.ordinal() + 1, Level.CHARACTER.ordinal())];
    }

    private List<String> splitAtLevel(String text, Level level) {
        if (level == Level.HEADING) return splitHeadings(text);
        Pattern pattern = switch (level) {
            case PARAGRAPH -> PARAGRAPH;
            case SENTENCE -> SENTENCE;
            case COMMA -> COMMA;
            case SPACE -> SPACE;
            default -> null;
        };
        return pattern == null ? List.of(text) : splitPreservingSeparators(text, pattern);
    }

    private List<String> splitHeadings(String text) {
        String[] lines = text.split("(?<=\\n)", -1);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String lineWithBreak : lines) {
            String line = lineWithBreak.stripTrailing();
            if (!current.isEmpty() && HEADING.matcher(line.strip()).matches()) {
                sections.add(current.toString());
                current.setLength(0);
            }
            current.append(lineWithBreak);
        }
        if (!current.isEmpty()) sections.add(current.toString());
        return sections.size() > 1 ? sections : List.of(text);
    }

    private List<String> splitPreservingSeparators(String text, Pattern separator) {
        List<String> pieces = new ArrayList<>();
        Matcher matcher = separator.matcher(text);
        int start = 0;
        while (matcher.find()) {
            int end = matcher.end();
            if (end > start) pieces.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) pieces.add(text.substring(start));
        return pieces.size() > 1 ? pieces : List.of(text);
    }
}
