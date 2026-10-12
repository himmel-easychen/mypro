package com.agicore.tender.service;

import com.agicore.tender.domain.*;
import com.agicore.persistence.RagPersistence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TenderAnalysisService {
    private static final List<String> ANALYSIS_GRAPH = List.of(
            "ParseTenderDocuments",
            "ClassifyTenderSections",
            "DetectTenderScope",
            "ExtractRequirementMatrix",
            "ExtractScoringMatrix",
            "ExtractSubmissionStructure",
            "ExtractPricingRules",
            "ExtractFatalRiskRules",
            "AssessEvidenceAvailability",
            "ProjectScoreAndReviewRisk",
            "DetectGapsAndUncertainties",
            "GenerateStrategyCandidates",
            "EvaluateStrategyOptions",
            "GenerateCollaborationAgenda",
            "BuildInitialOutline",
            "ReviewAnalysisArtifacts",
            "PublishAnalysisReady"
    );

    private final TenderWorkflowService workflow;
    private final TenderLlmAnalysisService llmAnalysis;
    private final RagPersistence ragPersistence;

    public TenderAnalysisService(TenderWorkflowService workflow) {
        this(workflow, null, null);
    }

    @Autowired
    public TenderAnalysisService(TenderWorkflowService workflow, TenderLlmAnalysisService llmAnalysis,
                                 RagPersistence ragPersistence) {
        this.workflow = workflow;
        this.llmAnalysis = llmAnalysis;
        this.ragPersistence = ragPersistence;
    }

    public RunAnalysisResult runAnalysis(String taskId, RunAnalysisRequest request) {
        TenderTask task = workflow.getTask(taskId);
        String actor = user(request == null ? null : request.triggeredBy());
        if (task.status() == TenderTaskStatus.CREATED) {
            task = workflow.transition(taskId, transition(TenderTaskStatus.ANALYZING, actor, "start analysis mode"));
        }
        if (task.status() != TenderTaskStatus.ANALYZING && task.status() != TenderTaskStatus.ANALYSIS_READY) {
            throw new IllegalStateException("ANALYSIS_CAN_ONLY_RUN_FROM_CREATED_ANALYZING_OR_ANALYSIS_READY");
        }

        Map<String, String> artifactIds = new LinkedHashMap<>();
        List<String> confirmationIds = new ArrayList<>();
        AnalysisInput input = AnalysisInput.from(task, request);
        Map<String, Object> extracted = extract(input);

        put(taskId, actor, artifactIds, TenderArtifactType.TENDER_BRIEF, TenderArtifactStatus.READY, artifact("TenderBrief", "brief", extracted, tenderBrief(input)));
        put(taskId, actor, artifactIds, TenderArtifactType.REQUIREMENT_MATRIX, TenderArtifactStatus.READY, artifact("RequirementMatrix", "requirements", extracted, requirementMatrix(input)));
        put(taskId, actor, artifactIds, TenderArtifactType.SCORING_MATRIX, TenderArtifactStatus.READY, artifact("ScoringMatrix", "scoring_items", extracted, scoringMatrix()));
        put(taskId, actor, artifactIds, TenderArtifactType.SUBMISSION_STRUCTURE_PACK, TenderArtifactStatus.READY, artifact("SubmissionStructurePack", "submission_sections", extracted, submissionStructurePack()));
        put(taskId, actor, artifactIds, TenderArtifactType.PRICING_RULE_PACK, TenderArtifactStatus.READY, artifact("PricingRulePack", "pricing_rules", extracted, pricingRulePack()));
        put(taskId, actor, artifactIds, TenderArtifactType.FATAL_RISK_RULE_PACK, TenderArtifactStatus.READY, artifact("FatalRiskRulePack", "fatal_risks", extracted, fatalRiskRulePack()));
        put(taskId, actor, artifactIds, TenderArtifactType.EVIDENCE_AVAILABILITY_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, artifact("EvidenceAvailabilityPack", "evidence_matches", extracted, evidenceAvailabilityPack(input)));
        put(taskId, actor, artifactIds, TenderArtifactType.SCORE_PROJECTION_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, artifact("ScoreProjectionPack", "score_projections", extracted, scoreProjectionPack()));
        put(taskId, actor, artifactIds, TenderArtifactType.GAP_LIST, TenderArtifactStatus.NEEDS_CONFIRMATION, artifact("GapList", "gaps", extracted, gapList(input)));
        put(taskId, actor, artifactIds, TenderArtifactType.STRATEGY_CANDIDATE_PACK, TenderArtifactStatus.READY, strategyCandidatePack());
        put(taskId, actor, artifactIds, TenderArtifactType.STRATEGY_EVALUATION_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, strategyEvaluationPack());
        put(taskId, actor, artifactIds, TenderArtifactType.COLLABORATION_AGENDA, TenderArtifactStatus.NEEDS_CONFIRMATION, collaborationAgenda());
        put(taskId, actor, artifactIds, TenderArtifactType.COLLABORATION_MEMORY_SEED_PACK,
                TenderArtifactStatus.NEEDS_CONFIRMATION, collaborationMemorySeedPack(artifactIds, input));
        put(taskId, actor, artifactIds, TenderArtifactType.INITIAL_OUTLINE, TenderArtifactStatus.READY, initialOutline());
        put(taskId, actor, artifactIds, TenderArtifactType.ANALYSIS_REVIEW_REPORT, TenderArtifactStatus.READY, reviewReport());

        put(taskId, actor, artifactIds, TenderArtifactType.TENDER_ANALYSIS_SUMMARY, TenderArtifactStatus.READY, tenderAnalysisSummary());
        put(taskId, actor, artifactIds, TenderArtifactType.BID_RISK_CHECKLIST, TenderArtifactStatus.NEEDS_CONFIRMATION, bidRiskChecklist());
        put(taskId, actor, artifactIds, TenderArtifactType.SCORING_OPPORTUNITY_REPORT, TenderArtifactStatus.NEEDS_CONFIRMATION, scoringOpportunityReport());
        put(taskId, actor, artifactIds, TenderArtifactType.MISSING_MATERIALS_LIST, TenderArtifactStatus.NEEDS_CONFIRMATION, missingMaterialsList());
        put(taskId, actor, artifactIds, TenderArtifactType.RESPONSE_PLAN_PREVIEW, TenderArtifactStatus.READY, responsePlanPreview());
        put(taskId, actor, artifactIds, TenderArtifactType.TENDER_TEMPLATE_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION,
                templatePack(input));
        put(taskId, actor, artifactIds, TenderArtifactType.CONFIRMED_CONTENT_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION,
                confirmedContentPack(artifactIds));
        put(taskId, actor, artifactIds, TenderArtifactType.GENERATION_INPUT_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION,
                generationInputPack(artifactIds));

        Map<String, Object> confirmation = workflow.createConfirmation(taskId, new CreateConfirmationRequest(
                "请确认高权重承诺、缺失材料和评分策略是否进入协作处理。",
                "分析模式 MVP 已生成评分预估、策略评估和缺口清单；涉及资料缺失或承诺边界的项目必须由投标人确认。",
                List.of(
                        Map.of("id", "ENTER_COLLABORATION", "label", "进入协作模式处理", "effect", "逐项确认补资料、承诺边界和策略选择"),
                        Map.of("id", "UPLOAD_MATERIALS_FIRST", "label", "先补充材料再评估", "effect", "补齐资料后重新运行分析模式")
                ),
                List.copyOf(artifactIds.values()),
                List.of("ScoreProjectionPack", "StrategyEvaluationPack", "GapList", "CollaborationAgenda"),
                actor
        ));
        confirmationIds.add(String.valueOf(confirmation.get("confirmationId")));

        if (task.status() == TenderTaskStatus.ANALYZING) {
            task = workflow.transition(taskId, transition(TenderTaskStatus.ANALYSIS_READY, actor, "analysis artifacts ready"));
        }
        task = workflow.transition(taskId, transition(TenderTaskStatus.AWAITING_CONFIRMATION, actor, "analysis requires bidder confirmation"));

        return new RunAnalysisResult(taskId, task.status(), ANALYSIS_GRAPH, artifactIds, confirmationIds, List.of());
    }

    private Map<String, Object> extract(AnalysisInput input) {
        if (llmAnalysis == null || ragPersistence == null || input.tenderDocumentIds().isEmpty()) return Map.of();
        List<Map<String, Object>> tender = chunks(input.tenderDocumentIds());
        if (tender.isEmpty()) throw new IllegalStateException("TENDER_DOCUMENT_HAS_NO_INDEXED_CHUNKS");
        return llmAnalysis.analyzeTender(tender, chunks(input.enterpriseDocumentIds()));
    }

    private List<Map<String, Object>> chunks(List<String> documentIds) {
        return ragPersistence.loadChunksByDocumentIds(documentIds, 1200).stream().map(chunk -> map(
                "chunk_id", chunk.chunkId(), "document_id", chunk.documentId(), "source_file", chunk.sourceFile(),
                "page", page(chunk.location()), "location", chunk.location(), "block_type", chunk.blockType(),
                "content", chunk.content(), "confidence", chunk.confidence())).toList();
    }

    private int page(String location) {
        if (location == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("page[:=](\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(location);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private Map<String, Object> artifact(String name, String key, Map<String, Object> extracted, Map<String, Object> fallback) {
        if (extracted.isEmpty()) return fallback;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifact", name);
        Object content = "brief".equals(key) ? extracted.get("tender_brief") : extracted.get(key);
        value.put("brief".equals(key) ? "content" : key, content);
        value.put("generated_by", extracted.get("generated_by"));
        value.put("analysis_status", "llm_extracted");
        return value;
    }

    private void put(String taskId, String actor, Map<String, String> artifactIds, TenderArtifactType type,
                     TenderArtifactStatus status, Map<String, Object> content) {
        Optional<TenderArtifact> existing = workflow.listArtifacts(taskId).stream()
                .filter(artifact -> artifact.type() == type)
                .findFirst();
        TenderArtifactVersion version = existing
                .map(artifact -> workflow.appendArtifactVersion(artifact.artifactId(), new AppendArtifactVersionRequest(
                        status, content, List.of(), List.of(), List.of(), List.of(), analysisMetadata(), actor)))
                .orElseGet(() -> workflow.createArtifact(taskId, new CreateArtifactRequest(
                        type, status, TenderMode.ANALYSIS, content, List.of(), List.of(), List.of(), List.of(), analysisMetadata(), actor)));
        artifactIds.put(type.name(), version.artifactId());
    }

    private Map<String, Object> templatePack(AnalysisInput input) {
        return map("artifact", "TenderTemplatePack", "template_status", "structure_only",
                "template_file_id", null, "template_file_required", true,
                "source_document_ids", input.tenderDocumentIds(),
                "sections_source_artifact", "SUBMISSION_STRUCTURE_PACK",
                "format_rules", List.of("preserve_required_section_order", "preserve_fixed_tables", "populate_required_fields"));
    }

    private Map<String, Object> confirmedContentPack(Map<String, String> ids) {
        return map("artifact", "ConfirmedContentPack", "confirmation_required", true,
                "evidence_binding_artifact_id", ids.get("CONFIRMED_EVIDENCE_BINDING_PACK"),
                "selected_strategy_artifact_id", ids.get("SELECTED_STRATEGY_PACK"),
                "pricing_strategy_artifact_id", ids.get("CONFIRMED_PRICING_STRATEGY"),
                "commitment_artifact_id", ids.get("COMMITMENT_CONFIRMATION_PACK"),
                "memory_seed_artifact_id", ids.get("COLLABORATION_MEMORY_SEED_PACK"));
    }

    private Map<String, Object> generationInputPack(Map<String, String> ids) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("tender_brief_id", ids.get("TENDER_BRIEF"));
        analysis.put("requirement_matrix_id", ids.get("REQUIREMENT_MATRIX"));
        analysis.put("scoring_matrix_id", ids.get("SCORING_MATRIX"));
        analysis.put("pricing_rule_pack_id", ids.get("PRICING_RULE_PACK"));
        analysis.put("fatal_risk_rule_pack_id", ids.get("FATAL_RISK_RULE_PACK"));
        analysis.put("submission_structure_pack_id", ids.get("SUBMISSION_STRUCTURE_PACK"));
        analysis.put("response_plan_preview_id", ids.get("RESPONSE_PLAN_PREVIEW"));
        return map("artifact", "GenerationInputPack", "contract_version", "1.0",
                "analysis_artifacts", analysis,
                "template_artifact_id", ids.get("TENDER_TEMPLATE_PACK"),
                "confirmed_content_artifact_id", ids.get("CONFIRMED_CONTENT_PACK"),
                "readiness_artifact_type", "GENERATION_READINESS_REPORT",
                "generation_policy", Map.of("allow_unconfirmed_artifacts", false, "require_citations", true));
    }

    private Map<String, Object> tenderBrief(AnalysisInput input) {
        return map(
                "artifact", "TenderBrief",
                "analysisMode", "analysis_mode_mvp",
                "projectName", input.projectName(),
                "tenderDocumentIds", input.tenderDocumentIds(),
                "referenceDocumentIds", input.referenceDocumentIds(),
                "scopeStatus", "requires_extraction",
                "coreRisks", List.of("招标文件规则尚需真实解析节点填充", "高权重承诺必须进入协作确认")
        );
    }

    private Map<String, Object> requirementMatrix(AnalysisInput input) {
        return map("artifact", "RequirementMatrix", "requirements", List.of(
                map("id", "REQ-MVP-001", "type", "mandatory", "text", "真实资格、实质性响应和合同要求待解析",
                        "source", input.sourceSummary(), "manualConfirmationRequired", true)
        ));
    }

    private Map<String, Object> scoringMatrix() {
        return map("artifact", "ScoringMatrix", "items", List.of(
                map("id", "SC-MVP-001", "category", "unknown", "maxScore", "unknown",
                        "rule", "真实评分细则待 ExtractScoringMatrix 节点填充", "highWeight", true,
                        "evidenceRequired", true)
        ), "totalScoreStatus", "requires_review");
    }

    private Map<String, Object> submissionStructurePack() {
        return map("artifact", "SubmissionStructurePack", "sections", List.of(
                map("id", "SEC-MVP-001", "name", "投标文件结构待解析", "status", "requires_extraction",
                        "boundRequirementIds", List.of("REQ-MVP-001"))
        ));
    }

    private Map<String, Object> pricingRulePack() {
        return map("artifact", "PricingRulePack", "pricingRules", List.of(),
                "status", "no_pricing_rule_extracted_yet", "manualConfirmationRequired", true);
    }

    private Map<String, Object> fatalRiskRulePack() {
        return map("artifact", "FatalRiskRulePack", "fatalRules", List.of(
                map("id", "FATAL-MVP-001", "trigger", "资格或实质性响应条款未完成真实解析",
                        "effect", "阻止直接进入生成", "blocksGeneration", true)
        ));
    }

    private Map<String, Object> evidenceAvailabilityPack(AnalysisInput input) {
        String status = input.enterpriseDocumentIds().isEmpty() ? "missing" : "uncertain";
        return map("artifact", "EvidenceAvailabilityPack", "records", List.of(
                map("targetId", "SC-MVP-001", "targetType", "scoring_item", "materialStatus", status,
                        "availableEvidenceRefs", List.of(), "missingMaterials", List.of(
                                map("name", "评分项支撑材料", "whyNeeded", "用于证明评分项响应能力",
                                        "impact", "缺失会导致该项预计得分为 unknown 或降档", "priority", "high")
                        ), "confirmationRequired", true)
        ));
    }

    private Map<String, Object> scoreProjectionPack() {
        return map("artifact", "ScoreProjectionPack", "projections", List.of(
                map("scoringItemId", "SC-MVP-001", "maxScore", "unknown",
                        "projectedScoreRange", map("status", "unknown"),
                        "projectedTier", "unknown",
                        "aiScoringRationale", "真实评分规则和证据状态尚未完成抽取，招标侧 AI 评分需后续节点补齐",
                        "humanReviewFocus", List.of("评分规则来源", "证明材料完整性", "高权重承诺边界"),
                        "deductionRisks", List.of("缺少证据时不得生成虚假高分响应"),
                        "evidenceStatus", "unknown", "manualReviewRequired", true, "confidence", "low")
        ));
    }

    private Map<String, Object> gapList(AnalysisInput input) {
        return map("artifact", "GapList", "gaps", List.of(
                map("id", "GAP-MVP-001", "type", "analysis_executor_placeholder", "severity", "high",
                        "description", "真实招标文件解析、评分抽取和证据匹配节点尚未接入",
                        "requiresConfirmation", true, "sourceDocumentIds", input.tenderDocumentIds())
        ));
    }

    private Map<String, Object> strategyCandidatePack() {
        return map("artifact", "StrategyCandidatePack", "visibility", "internal_only", "candidates", List.of(
                map("id", "STR-MVP-001", "type", "补资料后再冲分", "status", "requires_evaluation")
        ));
    }

    private Map<String, Object> strategyEvaluationPack() {
        return map("artifact", "StrategyEvaluationPack", "options", List.of(
                map("optionId", "OPT-MVP-001", "relatedScoringItemIds", List.of("SC-MVP-001"),
                        "expectedAiScoreDelta", map("status", "unknown"),
                        "expectedHumanScoreDelta", map("status", "unknown"),
                        "expectedFinalScoreRange", map("status", "unknown"),
                        "complianceResult", "conditional", "evidenceSupport", "unknown",
                        "manualReviewResult", "needs_review", "reviewFocus", List.of("补资料范围", "评分项证据绑定"),
                        "materialReadiness", "needs_material", "deliveryRisk", "medium",
                        "conflictResult", "none", "confirmationRequired", true, "finalStatus", "risky")
        ));
    }

    private Map<String, Object> collaborationAgenda() {
        return map("artifact", "CollaborationAgenda", "items", List.of(
                map("id", "CA-MVP-001", "question", "是否先补充评分项支撑材料并重新评估？",
                        "why", "未经 StrategyEvaluationPack 评估的方案不能直接给投标人选择",
                        "relatedStrategyOptionId", "OPT-MVP-001", "requiresHumanAnswer", true)
        ));
    }

    private Map<String, Object> collaborationMemorySeedPack(Map<String, String> artifactIds, AnalysisInput input) {
        return map("artifact", "CollaborationMemorySeedPack", "memory_seed_id", UUID.randomUUID().toString(),
                "items", List.of(
                        map("memory_seed_id", "SEED-SCOPE-001", "content_type", "scope",
                                "summary", "项目范围和关键文件需要投标人确认", "value", input.projectName(),
                                "source_artifact_ids", List.of(artifactIds.get(TenderArtifactType.TENDER_BRIEF.name()),
                                        artifactIds.get(TenderArtifactType.REQUIREMENT_MATRIX.name())),
                                "required_confirmation_type", "user_confirm",
                                "target_memory_layer", "authoritative_candidate", "status", "pending_confirmation"),
                        map("memory_seed_id", "SEED-EVIDENCE-001", "content_type", "evidence_status",
                                "summary", "企业支撑材料状态待确认", "value", "unknown",
                                "source_artifact_ids", List.of(artifactIds.get(TenderArtifactType.EVIDENCE_AVAILABILITY_PACK.name())),
                                "required_confirmation_type", "evidence_upload",
                                "target_memory_layer", "authoritative_candidate", "status", "pending_confirmation"),
                        map("memory_seed_id", "SEED-CONTEXT-001", "content_type", "analysis_context",
                                "summary", "分析阶段上下文，仅供协作解释使用", "value", input.sourceSummary(),
                                "source_artifact_ids", List.of(artifactIds.get(TenderArtifactType.COLLABORATION_AGENDA.name())),
                                "required_confirmation_type", "none",
                                "target_memory_layer", "reference_history", "status", "reference_only")
                ), "generated_by", "TenderAnalysisService", "contract_version", "2026-09-19");
    }

    private Map<String, Object> initialOutline() {
        return map("artifact", "InitialOutline", "sections", List.of(
                map("id", "OUT-MVP-001", "title", "投标响应框架待真实提交结构填充",
                        "status", "blocked_for_key_commitments")
        ));
    }

    private Map<String, Object> reviewReport() {
        return map("artifact", "AnalysisReviewReport", "blockingIssues", List.of(),
                "warnings", List.of("分析模式执行器已跑通产物链路，但真实抽取节点尚未接入"),
                "checks", map("scoreProjectionExists", true, "strategyEvaluationExists", true,
                        "customerVisibleStrategiesEvaluated", true, "highWeightItemsNeedReview", true));
    }

    private Map<String, Object> tenderAnalysisSummary() {
        return map("artifact", "TenderAnalysisSummary", "status", "ready_for_bidder_review",
                "summary", "分析模式 MVP 已生成项目范围、风险、评分和协作入口的结构化占位视图。");
    }

    private Map<String, Object> bidRiskChecklist() {
        return map("artifact", "BidRiskChecklist", "risks", List.of(
                map("id", "RISK-MVP-001", "level", "high", "type", "analysis_incomplete",
                        "description", "真实否决项和格式风险待解析，禁止直接生成正式投标承诺")
        ));
    }

    private Map<String, Object> scoringOpportunityReport() {
        return map("artifact", "ScoringOpportunityReport", "items", List.of(
                map("scoringItemId", "SC-MVP-001", "scoreRange", map("status", "unknown"),
                        "aiReviewRationale", "等待真实评分规则和证据匹配", "humanReviewFocus", List.of("材料完整性"))
        ));
    }

    private Map<String, Object> missingMaterialsList() {
        return map("artifact", "MissingMaterialsList", "materials", List.of(
                map("name", "评分项支撑材料", "priority", "high", "reason", "用于后续评分预估和人工复核")
        ));
    }

    private Map<String, Object> responsePlanPreview() {
        return map("artifact", "ResponsePlanPreview", "sections", List.of(
                map("name", "投标文件结构", "status", "requires_real_submission_structure")
        ));
    }

    private Map<String, Object> analysisMetadata() {
        return Map.of("graph", "analysis-mode", "contractVersion", "2026-09-19", "generatedBy", "TenderAnalysisService");
    }

    private TransitionRequest transition(TenderTaskStatus status, String actor, String reason) {
        return new TransitionRequest(status, actor, TenderTransitionTrigger.AGENT, reason, List.of(), List.of(), analysisMetadata());
    }

    private String user(String value) {
        return value == null || value.isBlank() ? "analysis-agent" : value;
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("Map requires key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private record AnalysisInput(String projectName, List<String> tenderDocumentIds, List<String> enterpriseDocumentIds,
                                 List<String> referenceDocumentIds) {
        static AnalysisInput from(TenderTask task, RunAnalysisRequest request) {
            List<String> tenderDocs = request == null || request.tenderDocumentIds() == null || request.tenderDocumentIds().isEmpty()
                    ? task.sourceDocumentIds()
                    : request.tenderDocumentIds();
            return new AnalysisInput(task.name(), list(tenderDocs),
                    list(request == null ? null : request.enterpriseDocumentIds()),
                    list(request == null ? null : request.referenceDocumentIds()));
        }
        Map<String, Object> sourceSummary() {
            return map("tenderDocumentIds", tenderDocumentIds, "enterpriseDocumentIds", enterpriseDocumentIds,
                    "referenceDocumentIds", referenceDocumentIds);
        }
        private static List<String> list(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
