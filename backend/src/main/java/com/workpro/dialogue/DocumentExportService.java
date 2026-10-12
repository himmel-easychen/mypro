package com.workpro.dialogue;

import com.workpro.domain.Contracts.ArtifactPath;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;

@Service
public class DocumentExportService {
    public ArtifactPath export(String taskId, String content) throws Exception {
        return exportDocx(taskId, content);
    }
    public ArtifactPath exportDocx(String taskId, String content) throws Exception {
        Path dir = Paths.get("runtime-artifacts").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String id = UUID.randomUUID().toString();
        Path file = dir.resolve(taskId + "-" + id + ".docx");
        byte[] bytes;
        try (var doc = new XWPFDocument()) { for (String line : content.split("\\R", -1)) doc.createParagraph().createRun().setText(line); try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { doc.write(out); } }
        bytes = Files.readAllBytes(file);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ArtifactPath(id, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", file.toString(), checksum, Instant.now().toString());
    }
    public ArtifactPath exportXlsx(String taskId, String content) throws Exception {
        Path dir = Paths.get("runtime-artifacts").toAbsolutePath().normalize(); Files.createDirectories(dir); String id=UUID.randomUUID().toString(); Path file=dir.resolve(taskId+"-"+id+".xlsx");
        try (var book=new XSSFWorkbook(); var out=Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { var sheet=book.createSheet("内容"); int i=0; for(String line:content.split("\\R",-1)){ Row row=sheet.createRow(i++); row.createCell(0).setCellValue(line); } book.write(out); }
        byte[] bytes=Files.readAllBytes(file); String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); return new ArtifactPath(id,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file.toString(),checksum,Instant.now().toString());
    }
}
