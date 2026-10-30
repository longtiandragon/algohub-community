# Only stops the command launched here on timeout, never Docker Desktop or containers.
function Backup-LocalLog {
    param([Parameter(Mandatory)][string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $previous = Get-Item -LiteralPath $Path
        if ($previous.Length -gt 0) {
            $archive = Join-Path $previous.DirectoryName 'startup-logs'
            New-Item -ItemType Directory -Path $archive -Force | Out-Null
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fffffff'
            Copy-Item -LiteralPath $previous.FullName -Destination (Join-Path $archive "$($previous.BaseName)-$stamp$($previous.Extension)") -ErrorAction Stop
        }
    }
}

function Invoke-LocalProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$LogDirectory,
        [ValidateRange(1,600)][int]$TimeoutSeconds = 30
    )
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $runId = [Guid]::NewGuid().ToString('N')
    $outLog = Join-Path $LogDirectory "command-$runId.out.log"
    $errLog = Join-Path $LogDirectory "command-$runId.err.log"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    try {
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            if (-not $process.HasExited) { $process.Kill() }
            throw "Command timed out after ${TimeoutSeconds}s. Check Docker Desktop and logs: $outLog ; $errLog . No containers or data volumes were reset."
        }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "Command failed (exit $($process.ExitCode)). Logs: $outLog ; $errLog"
        }
    } finally {
        $process.Dispose()
    }
}
