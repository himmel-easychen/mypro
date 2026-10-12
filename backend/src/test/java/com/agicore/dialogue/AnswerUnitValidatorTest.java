package com.agicore.dialogue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnswerUnitValidatorTest {
    @Test void rejectsQuantityDividedByRateAsRatio() {
        assertTrue(AnswerUnitValidator.invalidRatio("20分÷4分/年＝5倍"));
        assertTrue(AnswerUnitValidator.invalidRatio("100元 / (20元/月) = 5倍"));
        assertTrue(AnswerUnitValidator.invalidRatio("20分÷4分/年=5，即5倍"));
        assertTrue(AnswerUnitValidator.invalidRatio("20分 ÷ 4分/年 = 5（年），即最高分是单年得分的5倍"));
        assertTrue(AnswerUnitValidator.invalidRatio("20分÷4分/年×1年=5，即最高分是单年得分的5倍"));
    }
    @Test void preservesCorrectQuantityRatioAndDuration() {
        assertFalse(AnswerUnitValidator.invalidRatio("20分÷(1年×4分/年)=5倍"));
        assertFalse(AnswerUnitValidator.invalidRatio("20分÷4分=5倍"));
        assertFalse(AnswerUnitValidator.invalidRatio("100元÷20元/月=5月"));
    }
}
