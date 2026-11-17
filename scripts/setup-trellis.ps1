[CmdletBinding()]
param([string]$Developer = 'admin')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonDirectory = Join-Path $projectRoot '.tools\python'
$pythonPath = Join-Path $pythonDirectory 'python.exe'
$downloadDirectory = Join-Path $projectRoot '.cache\downloads'
$version = '3.14.10'
$expectedHash = '90905E64A840557D8C1BC4F5B74DB3D7EE85E3FFB217611CAC1C7813ACEA4159'

if (-not (Test-Path -LiteralPath $pythonPath)) {
    New-Item -ItemType Directory -Path $pythonDirectory, $downloadDirectory -Force | Out-Null
    $archivePath = Join-Path $downloadDirectory "python-$version-embed-amd64.zip"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest `
        -Uri "https://www.python.org/ftp/python/$version/python-$version-embed-amd64.zip" `
        -OutFile $archivePath -UseBasicParsing -TimeoutSec 60
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'Python archive checksum mismatch. No files were extracted.'
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $pythonDirectory -Force
}

# The embedded runtime needs explicit access to Trellis's local common modules.
@('python314.zip', '.', '../../.trellis/scripts', 'import site') |
    Set-Content -LiteralPath (Join-Path $pythonDirectory 'python314._pth') -Encoding ASCII

Push-Location -LiteralPath $projectRoot
try {
    & $pythonPath -c 'import sys; assert sys.version_info >= (3, 9); print(sys.version)'
    if ($LASTEXITCODE -ne 0) { throw 'Python runtime check failed.' }
    if (-not (Test-Path -LiteralPath '.trellis/scripts/task.py')) {
        throw 'Trellis project files are missing. Restore the checked-in .trellis directory first.'
    }
    if (-not (Test-Path -LiteralPath '.trellis/.developer')) {
        & $pythonPath '.trellis/scripts/init_developer.py' $Developer
        if ($LASTEXITCODE -ne 0) { throw 'Developer identity initialization failed.' }
    }
    & $pythonPath '.trellis/scripts/get_developer.py'
    if ($LASTEXITCODE -ne 0) { throw 'Developer identity validation failed.' }
    Write-Output 'Trellis is ready. Run: powershell -File scripts/trellis.ps1 tasks'
} finally {
    Pop-Location
}
