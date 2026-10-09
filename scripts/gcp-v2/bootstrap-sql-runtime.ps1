#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [string] $Project = 'pfi-asplanatti-fabrello-v2',
    [string] $Region = 'us-central1',
    [string] $Instance = 'pfi-postgres-v2',
    [string] $Database = 'pfi_e2e',
    [string] $RuntimeUser = 'pfi_app',
    [string] $RuntimeRole = 'pfi_runtime',
    [string] $DatabaseUrlSecret = 'pfi-e2e-database-url'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function New-UrlSafePassword {
    param([int] $ByteCount = 48)
    $bytes = [byte[]]::new($ByteCount)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    try {
        return [Convert]::ToBase64String($bytes).TrimEnd('=') -replace '\+', 'A' -replace '/', 'B'
    }
    finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Invoke-Gcloud {
    param([Parameter(Mandatory)][string[]] $Arguments)
    $result = & gcloud @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed (exit $LASTEXITCODE): $($Arguments[0..([Math]::Min(2, $Arguments.Length - 1))] -join ' ')"
    }
    return $result
}

function Add-SecretVersionDirect {
    param(
        [Parameter(Mandatory)][string] $SecretName,
        [Parameter(Mandatory)][string] $Value,
        [switch] $Regional
    )
    $token = ((Invoke-Gcloud -Arguments @('auth', 'print-access-token', "--project=$Project")) -join '').Trim()
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
    $body = @{ payload = @{ data = $encoded } } | ConvertTo-Json -Compress
    if ($Regional) {
        $uri = "https://secretmanager.$Region.rep.googleapis.com/v1/projects/$Project/locations/$Region/secrets/${SecretName}:addVersion"
    }
    else {
        $uri = "https://secretmanager.googleapis.com/v1/projects/$Project/secrets/${SecretName}:addVersion"
    }
    $null = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ Authorization = "Bearer $token" } `
        -ContentType 'application/json' -Body $body
    $token = $null
    $encoded = $null
    $body = $null
}

function Invoke-AdminSql {
    param(
        [Parameter(Mandatory)][string] $Sql,
        [string] $DatabaseName = $Database
    )
    $args = @(
        'sql', 'instances', 'execute-sql', $Instance,
        "--project=$Project",
        "--database=$DatabaseName",
        "--user=postgres",
        "--password-secret-version=projects/$Project/locations/$Region/secrets/$script:AdminSecret/versions/latest",
        "--sql=$Sql",
        '--partial-result-mode=FAIL_PARTIAL_RESULT',
        '--quiet'
    )
    $result = (Invoke-Gcloud -Arguments $args) -join "`n"
    if ($result -match '(?m)^\s*code:\s*([1-9]\d*)\s*$' -or $result -match 'Execution failed') {
        throw "Cloud SQL SQL statement failed: $result"
    }
    return $result
}

function New-RegionalSecret {
    param([Parameter(Mandatory)][string] $SecretName)
    $token = ((Invoke-Gcloud -Arguments @('auth', 'print-access-token', "--project=$Project")) -join '').Trim()
    $uri = "https://secretmanager.$Region.rep.googleapis.com/v1/projects/$Project/locations/$Region/secrets?secretId=$SecretName"
    $null = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ Authorization = "Bearer $token" } `
        -ContentType 'application/json' -Body '{}'
    $token = $null
}

function Remove-RegionalSecret {
    param([Parameter(Mandatory)][string] $SecretName)
    $token = ((Invoke-Gcloud -Arguments @('auth', 'print-access-token', "--project=$Project")) -join '').Trim()
    $uri = "https://secretmanager.$Region.rep.googleapis.com/v1/projects/$Project/locations/$Region/secrets/$SecretName"
    $null = Invoke-RestMethod -Method Delete -Uri $uri -Headers @{ Authorization = "Bearer $token" }
    $token = $null
}

$account = ((Invoke-Gcloud -Arguments @('config', 'get-value', 'account')) -join '').Trim()
if ($account -ne 'enzoandreaasplanatti@gmail.com') {
    throw "Unexpected gcloud account; expected enzoandreaasplanatti@gmail.com, got $account"
}

$state = ((Invoke-Gcloud -Arguments @('sql', 'instances', 'describe', $Instance, "--project=$Project", '--format=value(state)')) -join '').Trim()
if ($state -ne 'RUNNABLE') { throw "Cloud SQL instance is not RUNNABLE: $state" }
$initialDataApiAccess = ((Invoke-Gcloud -Arguments @('sql', 'instances', 'describe', $Instance, "--project=$Project", '--format=value(settings.dataApiAccess)')) -join '').Trim()
$dataApiWasEnabledAtStart = $initialDataApiAccess -eq 'ALLOW_DATA_API'

$dbNames = Invoke-Gcloud -Arguments @('sql', 'databases', 'list', "--instance=$Instance", "--project=$Project", '--format=value(name)')
if ($Database -notin $dbNames) { throw "Expected database $Database does not exist" }

$existingUsers = Invoke-Gcloud -Arguments @('sql', 'users', 'list', "--instance=$Instance", "--project=$Project", '--format=value(name)')
$runtimeUserExists = $RuntimeUser -in $existingUsers

$script:AdminSecret = "pfi-v2-sql-admin-bootstrap-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$adminSecretCreated = $false
$adminPasswordSet = $false
$dataApiWasEnabled = $false
$adminHasRuntimeRole = $false
$runtimePassword = $null
$adminPassword = $null
$replacementAdminPassword = $null
$adminSecretDeleted = $false
$bootstrapApiDisabled = -not $dataApiWasEnabledAtStart
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

try {
    New-RegionalSecret -SecretName $script:AdminSecret
    $adminSecretCreated = $true

    $adminPassword = New-UrlSafePassword
    Add-SecretVersionDirect -SecretName $script:AdminSecret -Value $adminPassword -Regional
    $null = Invoke-Gcloud -Arguments @('sql', 'users', 'set-password', 'postgres', "--instance=$Instance", "--project=$Project", "--password=$adminPassword", '--quiet')
    $adminPasswordSet = $true
    $adminPassword = $null

    $null = Invoke-Gcloud -Arguments @('sql', 'instances', 'patch', $Instance, "--project=$Project", '--data-api-access=ALLOW_DATA_API', '--quiet')
    $dataApiWasEnabled = $true

    $roleSql = @"
DO `$bootstrap`$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$RuntimeRole') THEN
    EXECUTE 'CREATE ROLE $RuntimeRole NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION';
  END IF;
END
`$bootstrap`$;
"@
    $null = Invoke-AdminSql -DatabaseName 'postgres' -Sql $roleSql
    $null = Invoke-AdminSql -DatabaseName 'postgres' -Sql "GRANT CONNECT ON DATABASE $Database TO $RuntimeRole;"
    # Cloud SQL's postgres account is not a true PostgreSQL SUPERUSER. Grant
    # temporary membership so it can set the dedicated schema owner, then
    # revoke it in finally even if a later step fails.
    $null = Invoke-AdminSql -DatabaseName 'postgres' -Sql "GRANT $RuntimeRole TO postgres;"
    $adminHasRuntimeRole = $true

    $null = Invoke-AdminSql -DatabaseName $Database -Sql "CREATE SCHEMA IF NOT EXISTS $RuntimeUser AUTHORIZATION $RuntimeRole;"
    $null = Invoke-AdminSql -DatabaseName $Database -Sql "ALTER SCHEMA $RuntimeUser OWNER TO $RuntimeRole;"
    $null = Invoke-AdminSql -DatabaseName $Database -Sql "GRANT USAGE, CREATE ON SCHEMA $RuntimeUser TO $RuntimeRole;"
    $null = Invoke-AdminSql -DatabaseName $Database -Sql "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA $RuntimeUser TO $RuntimeRole;"
    $null = Invoke-AdminSql -DatabaseName $Database -Sql "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA $RuntimeUser TO $RuntimeRole;"

    $runtimePassword = New-UrlSafePassword
    if ($runtimeUserExists) {
        # Resume safely after an interrupted bootstrap: rotate only this runtime
        # credential, then verify/fix its scoped membership below.
        $null = Invoke-Gcloud -Arguments @(
            'sql', 'users', 'set-password', $RuntimeUser,
            "--instance=$Instance", "--project=$Project",
            "--password=$runtimePassword", '--quiet'
        )
        $membershipSql = "GRANT $RuntimeRole TO $RuntimeUser;"
        $null = Invoke-AdminSql -Sql $membershipSql
    }
    else {
        $null = Invoke-Gcloud -Arguments @(
            'sql', 'users', 'create', $RuntimeUser,
            "--instance=$Instance", "--project=$Project",
            "--password=$runtimePassword", "--database-roles=$RuntimeRole", '--quiet'
        )
    }

    $null = Invoke-AdminSql -Sql "ALTER ROLE $RuntimeUser IN DATABASE $Database SET search_path TO $RuntimeUser, public;"

    $instanceInfo = Invoke-Gcloud -Arguments @('sql', 'instances', 'describe', $Instance, "--project=$Project", '--format=value(connectionName)')
    $connectionName = ($instanceInfo -join '').Trim()
    $jdbcUrl = "jdbc:postgresql:///${Database}?cloudSqlInstance=$connectionName&socketFactory=com.google.cloud.sql.postgres.SocketFactory&user=$RuntimeUser&password=$runtimePassword&sslmode=disable&currentSchema=$RuntimeUser"
    Add-SecretVersionDirect -SecretName $DatabaseUrlSecret -Value $jdbcUrl
    $jdbcUrl = $null

    $verificationSql = @"
SELECT CASE WHEN
  NOT u.rolsuper AND NOT u.rolcreatedb AND NOT u.rolcreaterole AND NOT u.rolreplication
  AND NOT pg_has_role('$RuntimeUser', 'cloudsqlsuperuser', 'MEMBER')
  AND pg_has_role('$RuntimeUser', '$RuntimeRole', 'MEMBER')
  AND NOT r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolreplication
  AND has_database_privilege('$RuntimeUser', '$Database', 'CONNECT')
  AND has_schema_privilege('$RuntimeUser', '$RuntimeUser', 'USAGE')
  AND has_schema_privilege('$RuntimeUser', '$RuntimeUser', 'CREATE')
THEN 'POSTGRES_RUNTIME_BOOTSTRAP=PASS'
ELSE 'POSTGRES_RUNTIME_BOOTSTRAP=FAIL'
END AS verification
FROM pg_roles u, pg_roles r
WHERE u.rolname = '$RuntimeUser' AND r.rolname = '$RuntimeRole';
"@
    $verification = (Invoke-AdminSql -Sql $verificationSql) -join "`n"
    Write-Output "Runtime privilege check result: $verification"
    if ($verification -notmatch 'POSTGRES_RUNTIME_BOOTSTRAP=PASS') {
        throw 'Runtime privilege verification did not return PASS'
    }

    $tablePrivilegeSql = @"
SELECT CASE WHEN
  has_schema_privilege('$RuntimeUser', '$RuntimeUser', 'USAGE')
  AND has_schema_privilege('$RuntimeUser', '$RuntimeUser', 'CREATE')
THEN 'SCHEMA_PRIVILEGES=CONNECT,USAGE,CREATE; role-owned objects support CRUD and sequences'
ELSE 'SCHEMA_PRIVILEGES=FAIL'
END AS privileges;
"@
    $privilegeResult = (Invoke-AdminSql -Sql $tablePrivilegeSql) -join "`n"
    if ($privilegeResult -notmatch 'SCHEMA_PRIVILEGES=CONNECT,USAGE,CREATE') {
        throw 'Runtime database/schema privilege verification failed'
    }

}
finally {
    $adminPassword = $null
    $runtimePassword = $null
    $jdbcUrl = $null
    if ($adminHasRuntimeRole) {
        try {
            $null = Invoke-AdminSql -DatabaseName 'postgres' -Sql "REVOKE $RuntimeRole FROM postgres;"
        }
        catch { $cleanupFailures.Add('temporary postgres role membership revocation') }
    }
    if ($adminPasswordSet) {
        try {
            if ($null -eq $replacementAdminPassword) { $replacementAdminPassword = New-UrlSafePassword }
            $null = Invoke-Gcloud -Arguments @('sql', 'users', 'set-password', 'postgres', "--instance=$Instance", "--project=$Project", "--password=$replacementAdminPassword", '--quiet')
        }
        catch { $cleanupFailures.Add('temporary postgres credential rotation') }
    }
    $replacementAdminPassword = $null
    if ($dataApiWasEnabled -and -not $dataApiWasEnabledAtStart) {
        try { $null = Invoke-Gcloud -Arguments @('sql', 'instances', 'patch', $Instance, "--project=$Project", '--data-api-access=DISALLOW_DATA_API', '--quiet') }
        catch { $cleanupFailures.Add('temporary Cloud SQL Data API disable') }
    }
    if ($adminSecretCreated) {
        try { Remove-RegionalSecret -SecretName $script:AdminSecret }
        catch { $cleanupFailures.Add('temporary admin Secret Manager deletion') }
        if ($cleanupFailures.Count -eq 0) { $adminSecretDeleted = $true }
    }
    if ($cleanupFailures.Count -gt 0) {
        throw "Bootstrap cleanup failed: $($cleanupFailures -join ', ')"
    }
}

Write-Output 'POSTGRES_RUNTIME_BOOTSTRAP=PASS'
Write-Output "runtime user: $RuntimeUser (member of $RuntimeRole; no cloudsqlsuperuser/SUPERUSER/CREATEDB/CREATEROLE/REPLICATION)"
Write-Output "database/schema privileges: CONNECT on $Database; USAGE, CREATE on schema $RuntimeUser; owns migrated app objects and their sequences"
Write-Output "temporary admin secret deleted: $(if ($adminSecretDeleted) { 'YES' } else { 'NO' })"
Write-Output "temporary bootstrap mechanism disabled: $(if ($bootstrapApiDisabled) { 'YES' } else { 'NOT_NEEDED' })"
