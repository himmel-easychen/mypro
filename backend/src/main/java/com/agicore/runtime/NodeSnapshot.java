package com.agicore.runtime;
public record NodeSnapshot(String nodeId,String status,String errorCode,String message,long durationMs,int attempts){}
