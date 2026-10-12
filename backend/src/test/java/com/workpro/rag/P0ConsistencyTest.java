package com.workpro.rag;

import com.workpro.persistence.RagPersistence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P0ConsistencyTest {
    @Test
    void mapsQualityAndPageCoverageToPersistedDocumentStatus() {
        assertEquals("ready", RagPersistence.documentStatus("accepted", true));
        assertEquals("needs_review", RagPersistence.documentStatus("retry_structure", true));
        assertEquals("needs_review", RagPersistence.documentStatus("accepted", false));
    }

    @Test
    void preservesUploadedFileContentTypeForAsyncMultipart() {
        assertEquals("application/pdf", RedisOcrQueue.contentTypeFor("招标文件.pdf"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                RedisOcrQueue.contentTypeFor("需求.docx"));
        assertEquals("application/vnd.ms-excel", RedisOcrQueue.contentTypeFor("清单.xls"));
        assertEquals("image/png", RedisOcrQueue.contentTypeFor("page.png"));
    }
}
