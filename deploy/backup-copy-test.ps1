# Isolated SCP fixtures; no real server, credentials or existing backups are used.
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'scripts/backup-copy.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) "algohub-backup-copy-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $fixture | Out-Null
$source = Join-Path $fixture 'source'
[IO.File]::WriteAllText($source, 'synthetic dump fixture')
$expected = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$destination = Join-Path $fixture 'destination'
$remotePath = '/var/backups/algohub/algohub-fixture.dump'
$local = Join-Path $destination 'algohub-fixture.dump'
$script:scenario = 'success'
$script:downloads = 0
function scp {
    $remote = $args[-2]
    $target = $args[-1]
    $global:LASTEXITCODE = 0
    if ($remote.EndsWith('.sha256')) {
        [IO.File]::WriteAllText($target, "$expected  $remotePath`n")
        return
    }
    $script:downloads++
    if ($script:scenario -eq 'success') { Copy-Item -LiteralPath $source -Destination $target }
    else {
        [IO.File]::WriteAllText($target, 'truncated download')
        if ($script:scenario -eq 'disconnect') { $global:LASTEXITCODE = 1 }
    }
}
function Copy-Fixture { Copy-VerifiedServerBackup -Server fixture -RemotePath $remotePath -Destination $destination -ExpectedHash $expected }
function Assert-NoPartial {
    if (Get-ChildItem -LiteralPath $destination -Filter '*.partial-*') { throw 'Partial download leaked' }
}
try {
    if (-not (Copy-Fixture).Copied) { throw 'Missing archive was not downloaded' }
    'PASS: new archive is downloaded and verified'
    if ((Copy-Fixture).Copied -or $script:downloads -ne 1) { throw 'Valid archive was needlessly downloaded' }
    'PASS: existing archive is hashed before being reused'
    [IO.File]::WriteAllText($local, 'corrupt existing archive')
    if (-not (Copy-Fixture).Copied -or (Get-FileHash $local).Hash -ine $expected) { throw 'Corrupt archive was not repaired' }
    'PASS: corrupt existing archive is replaced only by a verified copy'
    foreach ($failure in @('disconnect', 'bad-hash')) {
        [IO.File]::WriteAllText($local, 'existing evidence')
        $script:scenario = $failure
        $rejected = $false
        try { Copy-Fixture | Out-Null } catch { $rejected = $true }
        if (-not $rejected -or (Get-Content $local -Raw) -ne 'existing evidence') { throw "$failure overwrote existing data" }
        Assert-NoPartial
        "PASS: $failure preserves existing archive and removes only the temporary download"
    }
    $script:scenario = 'success'
    $result = Copy-VerifiedServerBackup -Server fixture -RemotePath $remotePath -Destination $destination
    if (-not $result.Copied) { throw 'Manifest-based deployment download was not repaired' }
    'PASS: deployment download validates the remote manifest'
    $before = $script:downloads
    $rejected = $false
    try { Copy-VerifiedServerBackup -Server fixture -RemotePath $remotePath -Destination $destination -ExpectedHash 'invalid' | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected -or $script:downloads -ne $before) { throw 'Invalid checksum was used for download' }
    Assert-NoPartial
    'PASS: invalid checksum is rejected before download'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolvedFixture.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolvedFixture -Leaf) -notlike 'algohub-backup-copy-test-*') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
