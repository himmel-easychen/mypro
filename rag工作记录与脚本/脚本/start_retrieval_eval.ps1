# Run locally; the embedding key is read interactively and is never written to disk.
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is already in use. Check the running backend configuration before evaluating.'
}
$taskKey = Read-Host 'DashScope embedding temporary API key' -AsSecureString
$taskPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskKey)
$taskSettings = @{
    WORKPRO_EMBEDDING_PROVIDER = 'dashscope'
    WORKPRO_EMBEDDING_BASE_URL = 'https://dashscope.aliyuncs.com/api/v1'
    WORKPRO_EMBEDDING_MODEL = 'text-embedding-v4'
    WORKPRO_EMBEDDING_DIMENSION = '1024'
    WORKPRO_RERANK_ENABLED = 'true'
    WORKPRO_RERANK_BASE_URL = 'http://127.0.0.1:8000'
    WORKPRO_RERANK_MODEL = 'BAAI/bge-reranker-base'
    WORKPRO_OCR_ENABLED = 'false'
}
$taskPrevious = @{}
try {
    $taskSettings['WORKPRO_EMBEDDING_API_KEY'] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr)
    foreach ($name in $taskSettings.Keys) {
        $taskPrevious[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $taskSettings[$name], 'Process')
    }
    $taskProcess = Start-Process -FilePath 'java' -ArgumentList '-jar','backend/target/workpro-backend-0.1.0.jar' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot 'tmp/retrieval-v3-live.log') -RedirectStandardError (Join-Path $taskRoot 'tmp/retrieval-v3-live.err.log') -PassThru
    Write-Output "Backend launcher PID: $($taskProcess.Id). Logs: tmp/retrieval-v3-live.log"
} finally {
    foreach ($name in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($name, $taskPrevious[$name], 'Process')
    }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr)
    $taskSettings.Clear()
    $taskKey.Dispose()
}
