param(
    [string]$DatabaseUrl = $env:TEST_DATABASE_URL,
    [string]$DatabaseUsername = $(if ($env:TEST_DATABASE_USERNAME) { $env:TEST_DATABASE_USERNAME } else { 'postgres' }),
    [string]$DatabasePassword = $(if ($null -ne $env:TEST_DATABASE_PASSWORD) { $env:TEST_DATABASE_PASSWORD } else { 'postgres' }),
    [switch]$SkipBuild,
    [string[]]$BackendArguments = @()
)

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
    throw 'Pass -DatabaseUrl or set TEST_DATABASE_URL to an isolated PostgreSQL database.'
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $backendRoot 'mvnw.cmd'
$classpathFile = Join-Path $backendRoot 'target\full-stack-test-classpath.txt'
$javaArgumentFile = Join-Path $backendRoot 'target\full-stack-test-java.args'

function ConvertTo-JavaArgumentFileToken([string]$Value) {
    $normalized = $Value.Replace('\', '/')
    if ($normalized -match '[\s#"]') {
        return '"' + $normalized.Replace('"', '\"') + '"'
    }
    return $normalized
}

Push-Location $backendRoot
try {
    if (-not $SkipBuild) {
        & $mavenWrapper -DskipTests test-compile dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0) {
            throw "Maven test compilation failed with exit code $LASTEXITCODE."
        }
    } elseif (-not (Test-Path -LiteralPath $classpathFile)) {
        throw "Classpath file does not exist: $classpathFile. Run once without -SkipBuild."
    }

    $env:TEST_DATABASE_URL = $DatabaseUrl
    $env:TEST_DATABASE_USERNAME = $DatabaseUsername
    $env:TEST_DATABASE_PASSWORD = $DatabasePassword

    $dependencyClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $fullClasspath = (Join-Path $backendRoot 'target\test-classes') + ';' +
            (Join-Path $backendRoot 'target\classes') + ';' + $dependencyClasspath
    $fullClasspath = $fullClasspath.Replace('\', '/')
    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        'java.exe'
    }

    $javaArguments = @(
        '-cp',
        (ConvertTo-JavaArgumentFileToken $fullClasspath),
        'app.realtimetranslator.testsupport.FullStackTestServer'
    ) + @($BackendArguments | ForEach-Object { ConvertTo-JavaArgumentFileToken $_ })
    [IO.File]::WriteAllLines($javaArgumentFile, $javaArguments, [Text.Encoding]::ASCII)

    & $javaExecutable "@$javaArgumentFile"
    if ($LASTEXITCODE -ne 0) {
        throw "Full-stack test server exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
    $ErrorActionPreference = $previousErrorActionPreference
}
