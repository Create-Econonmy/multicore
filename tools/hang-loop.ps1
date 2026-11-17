# Runs the GameTest server repeatedly until a shutdown hang shows up (MultiCore's shutdown watchdog logs
# "Shutdown is taking long"). Each run's log is kept; a hung server is stopped. Local test folders only.
param(
    [int]$Runs = 6,
    [int]$Minutes = 12,
    [string]$Out = "$env:TEMP\multicore-hangloop"
)
$proj = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $proj "run\gametest\logs\latest.log"

function Stop-TestServers {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -notmatch 'GradleDaemon' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue }
}

for ($i = 1; $i -le $Runs; $i++) {
    Remove-Item (Join-Path $proj "run\gametest\world") -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath (Join-Path $proj "gradlew.bat") -ArgumentList "runGameTestServer", "--console=plain" `
        -WorkingDirectory $proj -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Out "run$i.out") -RedirectStandardError (Join-Path $Out "run$i.err")
    $deadline = (Get-Date).AddMinutes($Minutes)
    $hung = $false
    while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern "Shutdown is taking long" -Quiet)) {
            Start-Sleep -Seconds 25
            $hung = $true
            break
        }
    }
    if (Test-Path $log) { Copy-Item $log (Join-Path $Out "run$i.log") -Force }
    $timedOut = -not $p.HasExited -and -not $hung
    if (-not $p.HasExited) { Stop-TestServers; Start-Sleep -Seconds 3; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    "run {0}: hung={1} timedOut={2} exit={3}" -f $i, $hung, $timedOut, $(if ($p.HasExited) { $p.ExitCode } else { "killed" })
    if ($hung) { break }
}
