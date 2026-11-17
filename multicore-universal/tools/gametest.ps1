# Runs the GameTest server once (fresh world), with a time limit, keeps the log and prints a short summary.
# Local test folders only. Extra Gradle arguments (e.g. -PnoTestmod) go in -GradleArgs.
# -Dir run\gametest-light -Ram 512M: only MultiCore Universal and the test mod, within the half-gigabyte limit.
param(
    [int]$Minutes = 30,
    [string]$Out = "$env:TEMP\multicore-universal-gametest",
    [string[]]$GradleArgs = @(),
    [string]$Dir = "run\gametest",
    [string]$Ram
)
$proj = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
# Two test servers in the same folders would share (and corrupt) one world and one log.
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -match 'fml.modFolders' }
if ($running) { "A test server of this project is already running (pid $($running.ProcessId -join ', ')); not starting another."; exit 1 }
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $proj "$Dir\logs\latest.log"
Remove-Item (Join-Path $proj "$Dir\world") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $proj "$Dir\gametestworld") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $log -Force -ErrorAction SilentlyContinue
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$args = @("runGameTestServer", "--console=plain", "-PgametestDir=$($Dir -replace '\\', '/')") + $GradleArgs
if ($Ram) { $args += "-PgametestRam=$Ram" }
$p = Start-Process -FilePath (Join-Path $proj "gradlew.bat") -ArgumentList $args -WorkingDirectory $proj -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Out "$stamp.out") -RedirectStandardError (Join-Path $Out "$stamp.err")
$deadline = (Get-Date).AddMinutes($Minutes)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 5 }
$timedOut = -not $p.HasExited
if ($timedOut) {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -notmatch 'GradleDaemon' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}
if (Test-Path $log) { Copy-Item $log (Join-Path $Out "$stamp.log") -Force }
"log: " + (Join-Path $Out "$stamp.log")
"timed out: $timedOut"
if (Test-Path $log) {
    Select-String -LiteralPath $log -Pattern 'Indexed \d+ mods|Coremod (not )?active|Mixin code from other mods|tests? (are now running|failed)|failed at|required tests|All \d+ required|benchmark\]|Threading error|switching to vanilla|still held the mod lock|Could not (weave|lock)' |
        ForEach-Object { $_.Line } | Select-Object -First 60
}
