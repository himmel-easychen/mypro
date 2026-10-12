package com.agicore.rag;

import java.util.List;

public record OcrPage(int page, List<OcrBlock> blocks) {}
