# Loads variables from the root .env file into the process environment,
# then starts the Spring Boot backend. Spring Boot does not read .env files
# natively, so this script bridges that gap for local development.

$envFile = Join-Path $PSScriptRoot "..\.env"

if (-not (Test-Path $envFile)) {
    Write-Host "No .env file found at $envFile — copy .env.example to .env first." -ForegroundColor Yellow
    Write-Host "Continuing with default values baked into application.yml." -ForegroundColor Yellow
} else {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $key, $value = $line.Split("=", 2)
            [System.Environment]::SetEnvironmentVariable($key.Trim(), $value.Trim())
        }
    }
    Write-Host "Loaded environment variables from $envFile" -ForegroundColor Green
}

Push-Location (Join-Path $PSScriptRoot "..\backend")
try {
    mvn spring-boot:run
} finally {
    Pop-Location
}
