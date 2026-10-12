package com.agicore.rag;

import com.agicore.persistence.RagPersistence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecursiveChunkerTest {
    private final RecursiveChunker chunker = new RecursiveChunker();

    @Test
    void prefersHeadingsAndKeepsHeadingWithItsSection() {
        String text = "一、项目概况\n项目名称和采购范围说明。项目服务周期为两年。\n\n"
                + "二、服务范围\n车辆维保服务覆盖广州多个区域，需按招标文件执行。";

        List<RecursiveChunker.ChunkPart> parts = chunker.split(text, 38);

        assertTrue(parts.size() >= 2);
        assertTrue(parts.get(0).content().startsWith("一、项目概况"));
        assertTrue(parts.stream().anyMatch(part -> part.content().startsWith("二、服务范围")));
        assertTrue(parts.stream().allMatch(part -> part.content().length() <= 38));
    }

    @Test
    void degradesFromParagraphToSentenceCommaAndWhitespace() {
        String text = "第一段包含很多信息，继续说明采购范围和服务要求。还要补充履约期限和验收方式！\n\n"
                + "第二段继续描述供应商资格，报价必须真实有效；合同签订后按期履约。";

        List<RecursiveChunker.ChunkPart> parts = chunker.split(text, 24);

        assertFalse(parts.isEmpty());
        assertTrue(parts.stream().allMatch(part -> part.content().length() <= 24));
        assertEquals(text.replaceAll("\\s+", ""), parts.stream()
                .map(RecursiveChunker.ChunkPart::content)
                .collect(java.util.stream.Collectors.joining())
                .replaceAll("\\s+", ""));
        assertTrue(parts.stream().allMatch(part -> !part.content().isBlank()));
    }

    @Test
    void fallsBackToCharacterSlicesWhenNoSeparatorsExist() {
        List<RecursiveChunker.ChunkPart> parts = chunker.split("abcdefghij", 3);

        assertEquals(List.of("abc", "def", "ghi", "j"),
                parts.stream().map(RecursiveChunker.ChunkPart::content).toList());
    }

    @Test
    void blankInputProducesNoChunks() {
        assertTrue(chunker.split("  \n\t", 10).isEmpty());
    }

    @Test
    void parentAndChildBudgetsCanBeComposed() {
        String text = "第一段包含采购范围、服务期限和验收要求。第二段包含报价规则与履约责任。"
                + "第三段包含违约处理和付款条件。";

        List<RecursiveChunker.ChunkPart> parents = chunker.split(text, 30);
        List<RecursiveChunker.ChunkPart> children = chunker.split(parents.get(0).content(), 10);

        assertTrue(parents.stream().allMatch(part -> part.content().length() <= 30));
        assertTrue(children.stream().allMatch(part -> part.content().length() <= 10));
    }

    @Test
    void persistedChunkCarriesExplicitParentId() {
        RagPersistence.Chunk chunk = new RagPersistence.Chunk(
                "child", "child", "child", "text", "page:1#parent:1#chunk:1", .8, "text", "parent");

        assertEquals("parent", chunk.parentChunkId());
    }
}
