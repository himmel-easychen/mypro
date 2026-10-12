package com.agicore.dialogue;
import com.agicore.rag.RetrievalContracts.*; import org.springframework.stereotype.Service;
@Service public class ResearchAgent { private final RetrievalService retrieval; public ResearchAgent(RetrievalService retrieval){this.retrieval=retrieval;} public RetrievalPack research(RetrievalRequest request){return retrieval.retrieve(request);} }
