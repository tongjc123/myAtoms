[CmdletBinding()]
param(
    [string]$Container = 'mysql8',
    [ValidateRange(1, 65535)][int]$Port = 3310
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $environmentPath) {
    Write-Output 'Local .env already exists; database accounts were not changed.'
    exit 0
}
$dockerCommand = Get-Command docker.exe -ErrorAction Stop
$probe = "SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('atoms_demo_dev','atoms_demo_test')) + (SELECT COUNT(*) FROM mysql.user WHERE User IN ('atoms_demo_app','atoms_demo_test'));"
$count = $probe | & $dockerCommand.Source exec -i $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names'
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect using the container root credential. No configuration written.' }
if (($count | Out-String).Trim() -ne '0') {
    throw 'Project database/account names already exist. Supply their connection settings in .env; no existing data was changed.'
}
function New-DatabaseSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$appPassword = New-DatabaseSecret
$testPassword = New-DatabaseSecret
$sql = @"
CREATE DATABASE atoms_demo_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE atoms_demo_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'atoms_demo_app'@'%' IDENTIFIED BY '$appPassword';
CREATE USER 'atoms_demo_test'@'%' IDENTIFIED BY '$testPassword';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON atoms_demo_dev.* TO 'atoms_demo_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON atoms_demo_test.* TO 'atoms_demo_test'@'%';
"@
$sql | & $dockerCommand.Source exec -i $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch'
if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed; inspect the dedicated project schemas before retrying.' }
$urlOptions = 'connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
@"
# Local development only; ignored by Git. Never print or copy these values into the frontend.
ATOMS_DB_URL=jdbc:mysql://127.0.0.1:$Port/atoms_demo_dev?$urlOptions
ATOMS_DB_USER=atoms_demo_app
ATOMS_DB_PASSWORD=$appPassword
ATOMS_TEST_DB_URL=jdbc:mysql://127.0.0.1:$Port/atoms_demo_test?$urlOptions
ATOMS_TEST_DB_USER=atoms_demo_test
ATOMS_TEST_DB_PASSWORD=$testPassword
ATOMS_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:8080,http://localhost:8080
ATOMS_COOKIE_SECURE=false
"@ | Set-Content -LiteralPath $environmentPath -Encoding ASCII
Write-Output 'Created dedicated development/test databases and users. Credentials saved to ignored .env.'
