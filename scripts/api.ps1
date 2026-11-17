[CmdletBinding()]
param(
    [ValidateSet('run', 'verify', 'package', 'compile')][string]$Action = 'run',
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $environmentPath)) {
    throw 'Missing .env. Run scripts/setup-database.ps1 or configure the keys shown in .env.example.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'A JDK is required. Pass -JavaHome with your JDK 21 installation directory.'
}
$allowedNames = @('ATOMS_DB_URL','ATOMS_DB_USER','ATOMS_DB_PASSWORD',
    'ATOMS_TEST_DB_URL','ATOMS_TEST_DB_USER','ATOMS_TEST_DB_PASSWORD',
    'ATOMS_ALLOWED_ORIGINS','ATOMS_COOKIE_SECURE',
    'ATOMS_ARK_API_KEY','ATOMS_ARK_BASE_URL','ATOMS_ARK_VIDEO_MODEL',
    'ATOMS_ARK_TEXT_MODEL','ATOMS_ARK_TEXT_MODEL_NAME','ATOMS_ARK_TEXT_TIMEOUT',
    'ATOMS_ARK_VIDEO_MODEL_25','ATOMS_ARK_VIDEO_MODEL_WAN',
    'ATOMS_ARK_CONNECT_TIMEOUT','ATOMS_ARK_REQUEST_TIMEOUT',
    'ATOMS_ARK_VIDEO_TIMEOUT','ATOMS_ARK_POLL_INTERVAL','ATOMS_ARK_ALLOW_PRIVATE_MEDIA',
    'ATOMS_QWEN_API_KEY','ATOMS_QWEN_BASE_URL','ATOMS_QWEN_TEXT_MODEL','ATOMS_QWEN_TEXT_MODEL_NAME',
    'ATOMS_QWEN_IMAGE_MODEL','ATOMS_QWEN_TEXT_TIMEOUT','ATOMS_QWEN_IMAGE_TIMEOUT',
    'ATOMS_QWEN_CONNECT_TIMEOUT','ATOMS_QWEN_REQUEST_TIMEOUT','ATOMS_QWEN_ALLOW_PRIVATE_MEDIA',
    'ATOMS_VIDEO_DURATION','ATOMS_VIDEO_RESOLUTION','ATOMS_VIDEO_RATIO','ATOMS_VIDEO_AUDIO',
    'ATOMS_BUDGET_LIMIT_CNY','ATOMS_VIDEO_RESERVE_CNY','ATOMS_TEXT_RESERVE_CNY')
$savedEnvironment = @{}
$namesToRestore = $allowedNames + @('JAVA_HOME','MAVEN_USER_HOME','SERVER_PORT')
foreach ($name in $namesToRestore) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$exitCode = 1
Push-Location -LiteralPath (Join-Path $projectRoot 'apps/api')
try {
    foreach ($line in Get-Content -LiteralPath $environmentPath -Encoding UTF8) {
        if ($line -match '^\s*(ATOMS_[A-Z_]+)=(.*)$' -and $allowedNames -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    # Some hosts export SERVER_PORT to child processes; pin the local API port so the workbench
    # always finds http://127.0.0.1:8080 instead of silently binding an unrelated port.
    $env:SERVER_PORT = '8080'
    $env:JAVA_HOME = $JavaHome
    $env:MAVEN_USER_HOME = Join-Path $projectRoot '.cache/maven-user'
    $repository = '-Dmaven.repo.local=' + (Join-Path $projectRoot '.cache/m2')
    if ($Action -eq 'run') {
        & './mvnw.cmd' $repository --batch-mode --no-transfer-progress spring-boot:run
    } else {
        if ($Action -ne 'compile' -and $env:ATOMS_TEST_DB_URL -notmatch '/atoms_demo_test(?:\?|$)') {
            throw 'Verification requires the isolated atoms_demo_test database.'
        }
        & './mvnw.cmd' $repository --batch-mode --no-transfer-progress $Action
    }
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
    foreach ($name in $namesToRestore) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
exit $exitCode
