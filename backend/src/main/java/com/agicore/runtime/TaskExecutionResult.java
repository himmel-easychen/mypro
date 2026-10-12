package com.agicore.runtime;
import java.util.List;
public record TaskExecutionResult(TaskRunStatus status,String currentNode,String message,List<NodeSnapshot> snapshots){}
