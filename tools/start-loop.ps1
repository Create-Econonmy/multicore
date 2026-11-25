# Starts the GameTest server over and over and only checks that it gets through mod loading (registries, configs) to
# the first test batch, then stops it. For start-up failures that only happen some of the time. Local test folders only.
param(
    [int]$Runs = 8,
    [string]$Dir = "run\gametest",
    [string]$Ram = "4G",
    [int]$TimeoutSeconds = 300,
    [string]$Out = "$env:TEMP\multicore-startloop"
)
$proj = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $proj "$Dir\logs\latest.log"
$gradleDir = $Dir -replace '\\', '/'

function Stop-TestServers {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -notmatch 'GradleDaemon' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue }
}

$ok = 0; $failed = 0
for ($i = 1; $i -le $Runs; $i++) {
    Remove-Item (Join-Path $proj "$Dir\world") -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath (Join-Path $proj "gradlew.bat") -ArgumentList "runGameTestServer", "-PgametestDir=$gradleDir", "-PgametestRam=$Ram", "--console=plain" `
        -WorkingDirectory $proj -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Out "start$i.out") -RedirectStandardError (Join-Path $Out "start$i.err")
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $result = "timeout"
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (-not (Test-Path $log)) { continue }
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($text -match 'Found unused register callbacks|Failed to start the minecraft server|Mod loading has failed') { $result = "FAILED"; break }
        if ($text -match 'Running test batch') { $result = "ok"; break }
        if ($p.HasExited) { $result = "exited"; break }
    }
    if (Test-Path $log) { Copy-Item $log (Join-Path $Out "start$i.log") -Force }
    Stop-TestServers; Start-Sleep -Seconds 2; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    if ($result -eq "ok") { $ok++ } else { $failed++ }
    "start {0}: {1}" -f $i, $result
}
"summary: {0} ok, {1} not ok out of {2}" -f $ok, $failed, $Runs
