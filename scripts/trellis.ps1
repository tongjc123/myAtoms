[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'tasks', 'current', 'start', 'validate', 'finish', 'doctor')]
    [string]$Action = 'status',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$TaskArguments = @()
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonPath = Join-Path $projectRoot '.tools\python\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    throw 'Project Python is missing. Run: powershell -File scripts/setup-trellis.ps1'
}

$previousContext = $env:TRELLIS_CONTEXT_ID
if (-not $env:TRELLIS_CONTEXT_ID) {
    if ($env:CODEX_THREAD_ID) {
        $env:TRELLIS_CONTEXT_ID = $env:CODEX_THREAD_ID
    } else {
        $env:TRELLIS_CONTEXT_ID = 'atoms-local'
    }
}

Push-Location -LiteralPath $projectRoot
try {
    switch ($Action) {
        'status'   { & $pythonPath '.trellis/scripts/get_context.py' }
        'tasks'    { & $pythonPath '.trellis/scripts/task.py' list @TaskArguments }
        'current'  { & $pythonPath '.trellis/scripts/task.py' current --source }
        'start'    { & $pythonPath '.trellis/scripts/task.py' start @TaskArguments }
        'validate' { & $pythonPath '.trellis/scripts/task.py' validate @TaskArguments }
        'finish'   { & $pythonPath '.trellis/scripts/task.py' finish }
        'doctor'   { & $pythonPath 'scripts/verify-project.py' }
    }
    $resultCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:TRELLIS_CONTEXT_ID = $previousContext
}
exit $resultCode
