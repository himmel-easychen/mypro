package com.workpro.dialogue;

import java.util.regex.Pattern;

/** Rejects an explicit quantity/rate expression incorrectly claimed to be dimensionless. */
public final class AnswerUnitValidator {
    private AnswerUnitValidator() {}
    private static final Pattern QUANTITY_OVER_RATE = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(分|元|万元|人|个|公里|千米)\\s*[÷/]\\s*"
            + "[（(]?\\s*\\d+(?:\\.\\d+)?\\s*\\1\\s*[/／]\\s*(年|月|日|天|小时)\\s*[）)]?"
            + "(?:\\s*[×*]\\s*\\d+(?:\\.\\d+)?\\s*(?:年|月|日|天|小时))?"
            + "\\s*[=＝]\\s*\\d+(?:\\.\\d+)?\\s*(年|月|日|天|小时)?");

    public static boolean invalidRatio(String answer) {
        if (answer == null) return false;
        var matcher = QUANTITY_OVER_RATE.matcher(answer);
        while (matcher.find()) {
            if (!matcher.group(2).equals(matcher.group(3))) return true;
            String tail = answer.substring(matcher.end(), Math.min(answer.length(), matcher.end() + 20));
            if (matcher.group(3) != null && tail.contains("倍")) return true;
        }
        return false;
    }
}
