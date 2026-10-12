package com.workpro.rag;

import org.springframework.stereotype.Component;
import java.text.Normalizer;

/** Callable normalization tool shared by all parser layers before evaluation. */
@Component
public final class TextNormalizationTool {
    public String normalize(String input) {
        if (input == null || input.isEmpty()) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                // Some export paths serialize line breaks as the two literal characters
                // "\\n"/"\\r\\n". Decode those before applying whitespace rules.
                .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u0000", "").replace("\u00ad", "")
                .replaceAll("(?i)([A-Za-z])-[\\t ]*\\n[\\t ]*([A-Za-z])", "$1$2")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("[ \\t]*\\|[ \\t]*", " | ")
                .replaceAll("\\n{2,}", "\n")
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .trim();
    }
}
