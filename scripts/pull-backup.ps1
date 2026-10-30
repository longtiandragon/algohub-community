# Copy the production database backups to this computer (off-site copy): backup/server/ (git-ignored).
#   scripts/pull-backup.ps1            download every server backup that is not here yet, verify SHA-256
#   scripts/pull-backup.ps1 -Latest    only the newest one
# Local copies older than 60 days are removed, but the newest 20 are always kept.
# Run it daily with Task Scheduler, e.g.:
#   schtasks /Create /TN "AlgoHub backup pull" /SC DAILY /ST 12:30 /TR "pwsh -NoProfile -File C:\path\to\algohub\scripts\pull-backup.ps1"
# SSH: host alias "algohub" in ~/.ssh/config with the key ~/.ssh/algohub_deploy.
param([switch]$Latest)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$server = 'algohub'
$target = Join-Path $projectRoot 'backup/server'
New-Item -ItemType Directory -Path $target -Force | Out-Null
. (Join-Path $PSScriptRoot 'backup-copy.ps1')

# Check saved manifests before accepting current hashes. Legacy dumps may lack a manifest.
$listScript = @'
set -euo pipefail
cd /var/backups/algohub
shopt -s nullglob
archives=(algohub-*.dump)
((${#archives[@]})) || exit 0
while IFS= read -r archive; do
  [[ "$archive" =~ ^algohub-[0-9A-Za-z._-]+\.dump$ ]] || exit 1
  actual="$(sha256sum "$archive")"
  actual="${actual%% *}"
  if [[ -f "$archive.sha256" ]]; then
    read -r expected _ < "$archive.sha256"
    [[ "$expected" =~ ^[[:xdigit:]]{64}$ && "${expected,,}" == "$actual" ]] || {
      echo "Stored checksum mismatch: $archive" >&2; exit 1;
    }
  fi
  printf '%s  %s\n' "$actual" "$archive"
done < <(ls -1t -- "${archives[@]}")
'@
$listing = ($listScript -replace "`r", '') | & ssh -o BatchMode=yes $server "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) { throw "Cannot list backups over ssh ($server)." }
$remote = @()
foreach ($line in $listing) {
    if ($line -match '^([0-9a-f]{64})\s+\*?(algohub-[0-9A-Za-z._-]+\.dump)$') { $remote += [pscustomobject]@{ Hash = $Matches[1]; Name = $Matches[2] } }
}
if ($Latest) { $remote = @($remote | Select-Object -First 1) }

$copied = 0
foreach ($item in $remote) {
    $result = Copy-VerifiedServerBackup -Server $server -RemotePath "/var/backups/algohub/$($item.Name)" -Destination $target -ExpectedHash $item.Hash
    if ($result.Copied) {
        Write-Output "copied and verified $($item.Name) ($([math]::Round((Get-Item -LiteralPath $result.Path).Length / 1KB)) KB)"
        $copied++
    }
}

$dumps = @(Get-ChildItem -LiteralPath $target -Filter 'algohub-*.dump' | Sort-Object LastWriteTime -Descending)
foreach ($old in ($dumps | Select-Object -Skip 20 | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-60) })) {
    Remove-Item -LiteralPath $old.FullName, "$($old.FullName).sha256" -Force -ErrorAction SilentlyContinue
    Write-Output "removed old local copy $($old.Name)"
}
Write-Output "$copied downloaded/repaired backup(s); $($remote.Count) verified; $(@(Get-ChildItem -LiteralPath $target -Filter 'algohub-*.dump').Count) kept in $target"
