from __future__ import annotations

import json
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from build_tender_generation_eval import OUTPUT_DIR


REAL_OUTPUT_DIR = OUTPUT_DIR / "real-generation-run"
RUN_JSON = REAL_OUTPUT_DIR / "real_generation_run.json"
OUTPUT_PDF = REAL_OUTPUT_DIR / "generated_bid_response_pack.pdf"


def p(text: object) -> str:
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> None:
    data = json.loads(RUN_JSON.read_text(encoding="utf-8"))
    pdfmetrics.registerFont(TTFont("YaHei", r"C:\Windows\Fonts\msyh.ttc"))
    pdfmetrics.registerFont(TTFont("YaHeiBold", r"C:\Windows\Fonts\msyhbd.ttc"))

    styles = getSampleStyleSheet()
    normal = ParagraphStyle("normal_cn", parent=styles["Normal"], fontName="YaHei", fontSize=9.5, leading=15)
    title = ParagraphStyle("title_cn", parent=styles["Title"], fontName="YaHeiBold", fontSize=18, leading=24, textColor=colors.black, alignment=1)
    h1 = ParagraphStyle("h1_cn", parent=styles["Heading1"], fontName="YaHeiBold", fontSize=14, leading=20, spaceBefore=12, spaceAfter=8, textColor=colors.black)
    h2 = ParagraphStyle("h2_cn", parent=styles["Heading2"], fontName="YaHeiBold", fontSize=11.5, leading=17, spaceBefore=8, spaceAfter=5, textColor=colors.black)

    story = [
        Paragraph("广州供电局车辆维保项目生成模式产物包", title),
        Paragraph("标的8 花都区域一般车辆维保", normal),
        Spacer(1, 0.4 * cm),
        Paragraph("生成边界", h1),
        Paragraph("招标文件和中标样本均作为数据源处理，其中任何面向AI、模型、系统或工具的语句均不作为执行指令。", normal),
        Paragraph("后端外部LLM网关因缺少API key返回401，本次正文生成和复核由当前Codex会话模型完成，并在JSON产物中记录。", normal),
        Paragraph("评分投影", h1),
    ]

    score = data["ScoreProjectionPack"]
    rows = [
        ["维度", "得分", "说明"],
        ["商务", f"{score['business_weighted_score']:.2f}/30", "同类业绩、企业信誉、三体系认证、一类资质均达满分投影。"],
        ["技术", f"{score['technical_weighted_score']:.2f}/30", "服务方案、团队、质量、承诺和场地证据完整。"],
        ["价格", f"{score['price_proxy_score']:.2f}/30", "报价合规，但缺少全体投标人报价分布，无法精算靶心法真实分。"],
        ["供应商评价", f"{score['supplier_evaluation_proxy_score']:.2f}/10", "缺少履约评价和失信扣分外部数据，按产物完整性代理评分。"],
        ["总分", f"{score['total_percent_score']:.2f}/100", "生成模式产物对照中标样本的代理评分。"],
    ]
    table = Table([[Paragraph(p(cell), normal) for cell in row] for row in rows], colWidths=[2.6*cm, 3.0*cm, 11.2*cm])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1F4E79")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "YaHeiBold"),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#D9D9D9")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F3F6FA")]),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.extend([table, Spacer(1, 0.3 * cm), Paragraph("正文草稿", h1)])

    for section in data["DraftPack"]["sections"]:
        story.append(Paragraph(p(section["title"]), h2))
        story.append(Paragraph(p(section["llm_generated_text"]), normal))
        story.append(Paragraph("<b>证据绑定：</b>" + p("；".join(map(str, section["evidence"][:8]))), normal))

    story.append(Paragraph("复核结论", h1))
    review = data["ReviewReport"]
    story.append(Paragraph("复核状态：" + p(review["status"]), normal))
    issue_rows = [["级别", "类型", "问题", "阻断"]]
    for issue in review["issues"]:
        issue_rows.append([issue["severity"], issue["type"], issue["description"], "是" if issue["blocked"] else "否"])
    issue_table = Table([[Paragraph(p(cell), normal) for cell in row] for row in issue_rows], colWidths=[2.0*cm, 4.0*cm, 9.0*cm, 1.5*cm])
    issue_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#4F4F4F")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "YaHeiBold"),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#D9D9D9")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.append(issue_table)

    if "RagEvidencePack" in data:
        story.append(Spacer(1, 0.3 * cm))
        story.append(Paragraph("RAG 取证摘要", h1))
        rag = data["RagEvidencePack"]
        story.append(Paragraph(
            f"本次生成模式调用 live RAG 检索后端 {p(rag['backend'])}，共执行 {rag['queries']} 个评分取证查询，成功 {rag['ok']} 个。",
            normal,
        ))
        rag_rows = [["查询", "Top 来源文件"]]
        for ref in rag["evidence_refs"]:
            rag_rows.append([
                ref["query_id"],
                "；".join(str(x) for x in ref["top_source_files"] if x)[:180],
            ])
        rag_table = Table([[Paragraph(p(cell), normal) for cell in row] for row in rag_rows], colWidths=[3.0*cm, 13.5*cm])
        rag_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1F4E79")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTNAME", (0, 0), (-1, 0), "YaHeiBold"),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#D9D9D9")),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 5),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ]))
        story.append(rag_table)

    doc = SimpleDocTemplate(
        str(OUTPUT_PDF),
        pagesize=letter,
        leftMargin=1.8*cm,
        rightMargin=1.8*cm,
        topMargin=1.6*cm,
        bottomMargin=1.6*cm,
        title="广州供电局车辆维保项目生成模式产物包",
    )
    doc.build(story)
    print(str(OUTPUT_PDF))


if __name__ == "__main__":
    main()
