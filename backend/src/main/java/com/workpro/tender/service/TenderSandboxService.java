package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationInputPack;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Controlled materialization boundary for tender artifacts. */
@Service
public class TenderSandboxService {
    private static final Set<String> ALLOWED_OPERATIONS = Set.of("fill_template", "render_docx", "export_artifact");
    private final TenderDocxRenderer renderer;
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();

    public TenderSandboxService(TenderDocxRenderer renderer) { this.renderer = renderer; }

    public TenderDocxRenderer.RenderedDocument renderDocx(String taskId, GenerationInputPack pack,
                                                          List<TenderSectionWriter.SectionDraft> drafts,
                                                          String actor) throws Exception {
        String operation = pack.templatePack().containsKey("template_file_path") ? "fill_template" : "render_docx";
        authorize(operation, taskId, actor);
        try {
            TenderDocxRenderer.RenderedDocument result = renderer.render(taskId, pack, drafts);
            Path output = Path.of(result.path()).toAbsolutePath().normalize();
            audit.add(new AuditEntry(taskId, operation, actor, "SUCCEEDED", output.toString(), result.checksum()));
            return result;
        } catch (Exception error) {
            audit.add(new AuditEntry(taskId, operation, actor, "FAILED", "", error.getClass().getSimpleName()));
            throw error;
        }
    }

    public List<AuditEntry> audit(String taskId) {
        return audit.stream().filter(entry -> entry.taskId().equals(taskId)).toList();
    }

    private void authorize(String operation, String taskId, String actor) {
        if (!ALLOWED_OPERATIONS.contains(operation)) throw new IllegalArgumentException("SANDBOX_OPERATION_NOT_ALLOWED");
        if (taskId == null || taskId.isBlank() || actor == null || actor.isBlank())
            throw new IllegalArgumentException("SANDBOX_CONTEXT_REQUIRED");
    }

    public record AuditEntry(String taskId, String operation, String actor, String status,
                             String outputPath, String checksum) {}
}
