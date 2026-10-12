# Run locally; the embedding key is read interactively and is never written to disk.
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is already in use. Check the running backend configuration before evaluating.'
}
$taskKey = Read-Host 'DashScope embedding temporary API key' -AsSecureString
$taskPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskKey)
$taskSettings = @{
    AGICORE_EMBEDDING_PROVIDER = 'dashscope'
    AGICORE_EMBEDDING_BASE_URL = 'https://dashscope.aliyuncs.com/api/v1'
    AGICORE_EMBEDDING_MODEL = 'text-embedding-v4'
    AGICORE_EMBEDDING_DIMENSION = '1024'
    AGICORE_RERANK_ENABLED = 'true'
    AGICORE_RERANK_BASE_URL = 'http://127.0.0.1:8000'
    AGICORE_RERANK_MODEL = 'BAAI/bge-reranker-base'
    AGICORE_OCR_ENABLED = 'false'
}
$taskPrevious = @{}
try {
    $taskSettings['AGICORE_EMBEDDING_API_KEY'] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr)
    foreach ($name in $taskSettings.Keys) {
        $taskPrevious[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $taskSettings[$name], 'Process')
    }
    $taskProcess = Start-Process -FilePath 'java' -ArgumentList '-jar','backend/target/agicore-backend-0.1.0.jar' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot 'tmp/retrieval-v3-live.log') -RedirectStandardError (Join-Path $taskRoot 'tmp/retrieval-v3-live.err.log') -PassThru
    Write-Output "Backend launcher PID: $($taskProcess.Id). Logs: tmp/retrieval-v3-live.log"
} finally {
    foreach ($name in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($name, $taskPrevious[$name], 'Process')
    }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr)
    $taskSettings.Clear()
    $taskKey.Dispose()
}
