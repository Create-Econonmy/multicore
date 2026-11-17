# Starts the local test server (run/server, `gradlew runServer`), types the console commands from a script file into
# it, waits for it to stop (the script should end with "stop"), and stops it forcibly if it does not stop in time.
# Only ever touches this project's run/server folder.
#
# Script lines:  wait <regex>     wait until the server log matches (e.g. "Done \(")
#                sleep <seconds>
#                # comment
#                anything else    typed into the server console
param(
    [string]$Script = (Join-Path $PSScriptRoot "local-server-commands.txt"),
    [string]$Out = "$env:TEMP\multicore-universal-local-server",
    [int]$Minutes = 30,
    [string[]]$GradleArgs = @()
)
$proj = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -match 'fml.modFolders' }
if ($running) { "A test server of this project is already running (pid $($running.ProcessId -join ', ')); not starting another."; exit 1 }
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $proj "run\server\logs\latest.log"
Remove-Item $log -Force -ErrorAction SilentlyContinue

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = Join-Path $proj "gradlew.bat"
$psi.Arguments = (@("runServer", "--console=plain") + $GradleArgs) -join " "
$psi.WorkingDirectory = $proj
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$p = New-Object System.Diagnostics.Process
$p.StartInfo = $psi
$outFile = Join-Path $Out "console.out"
Remove-Item $outFile -Force -ErrorAction SilentlyContinue
Register-ObjectEvent -InputObject $p -EventName OutputDataReceived -Action { if ($EventArgs.Data) { Add-Content -LiteralPath $Event.MessageData -Value $EventArgs.Data } } -MessageData $outFile | Out-Null
Register-ObjectEvent -InputObject $p -EventName ErrorDataReceived -Action { if ($EventArgs.Data) { Add-Content -LiteralPath $Event.MessageData -Value $EventArgs.Data } } -MessageData $outFile | Out-Null
[void]$p.Start()
$p.BeginOutputReadLine()
$p.BeginErrorReadLine()
# The default stdin writer starts with a UTF-8 byte order mark, which garbles the first command.
$stdin = New-Object System.IO.StreamWriter($p.StandardInput.BaseStream, (New-Object System.Text.UTF8Encoding($false)))
$stdin.AutoFlush = $true
$deadline = (Get-Date).AddMinutes($Minutes)

function Wait-Log([string]$pattern) {
    while ((Get-Date) -lt $deadline -and -not $p.HasExited) {
        if ((Test-Path $log) -and (Select-String -LiteralPath $log -Pattern $pattern -Quiet)) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

foreach ($line in Get-Content $Script) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith("#")) { continue }
    if ($p.HasExited) { "server exited early"; break }
    if ($line -match '^wait (.+)$') {
        $ok = Wait-Log $Matches[1]
        "{0:HH:mm:ss} wait '{1}': {2}" -f (Get-Date), $Matches[1], $ok
        if (-not $ok) { break }
    } elseif ($line -match '^sleep (\d+)$') {
        Start-Sleep -Seconds ([int]$Matches[1])
    } else {
        "{0:HH:mm:ss} > {1}" -f (Get-Date), $line
        $stdin.WriteLine($line)
    }
}
while (-not $p.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
if (-not $p.HasExited) {
    "server did not stop in time; stopping it"
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($proj) -and $_.CommandLine -notmatch 'GradleDaemon' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue }
}
if (Test-Path $log) { Copy-Item $log (Join-Path $Out "latest.log") -Force }
"exit: " + $(if ($p.HasExited) { $p.ExitCode } else { "killed" })
