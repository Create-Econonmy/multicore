# Summarises the brute-force GameTest (MultiCoreFuzzTests): groups the warnings and errors logged while every block
# entity and entity type ticked with MultiCore on, and again with it off, and lists the ones that only happened with
# MultiCore on. Those are the candidates for MultiCore bugs; the rest are the mods' own problems.
param(
    [string]$Log = (Join-Path (Split-Path -Parent $PSScriptRoot) "run\gametest\logs\latest.log"),
    [int]$Top = 40
)
$lines = Get-Content -LiteralPath $Log
$header = '^\[\d{2}\w{3}\d{4} [\d:.]+\] \[(?<thread>[^\]]+)/(?<level>[A-Z]+)\] \[(?<logger>[^\]]*)\]: (?<msg>.*)$'

function Find-Marker([string]$text) {
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].Contains($text)) { return $i } }
    return -1
}

# Turns one log entry into a signature that is the same for the same problem at other coordinates, ids or times.
function Get-Signature([string]$msg, [string[]]$stack) {
    $m = $msg -replace '\b[0-9a-f]{8}-[0-9a-f-]{27}\b', '<uuid>' -replace '-?\d+(\.\d+)?', '#' -replace '\s+', ' '
    if ($m.Length -gt 160) { $m = $m.Substring(0, 160) }
    $ex = $stack | Where-Object { $_ -match '^\s*(Caused by: )?[\w.$]+(Exception|Error|Throwable)\b' } | Select-Object -Last 1
    $frame = $stack | Where-Object { $_ -match '^\s+at ' -and $_ -notmatch 'at (java\.|jdk\.|sun\.|com\.multicore\.)' } | Select-Object -First 1
    $ex = if ($ex) { ($ex.Trim() -replace ':.*$', '') } else { '' }
    $frame = if ($frame) { ($frame.Trim() -replace '\(.*\)$', '' -replace '^at ', '') } else { '' }
    return ("{0} | {1} | {2}" -f $m, $ex, $frame).Trim(' ', '|')
}

function Get-Problems([int]$from, [int]$to) {
    $found = @{}
    $i = $from
    while ($i -lt $to) {
        if ($lines[$i] -match $header -and ($Matches.level -eq 'ERROR' -or $Matches.level -eq 'WARN')) {
            $msg = $Matches.msg; $thread = $Matches.thread
            $j = $i + 1; $stack = @()
            while ($j -lt $to -and $lines[$j] -notmatch '^\[\d{2}\w{3}\d{4} ') { $stack += $lines[$j]; $j++ }
            $sig = Get-Signature $msg $stack
            if (-not $found.ContainsKey($sig)) { $found[$sig] = [pscustomobject]@{ Count = 0; Thread = $thread; Example = $msg } }
            $found[$sig].Count++
            $i = $j
        } else { $i++ }
    }
    return $found
}

$bp = Find-Marker '[MultiCore fuzz] BEGIN parallel'; $ep = Find-Marker '[MultiCore fuzz] END parallel'
$bv = Find-Marker '[MultiCore fuzz] BEGIN vanilla'; $ev = Find-Marker '[MultiCore fuzz] END vanilla'
if ($bp -lt 0 -or $ep -lt 0) { "The fuzz test did not run to the end of the parallel phase in $Log"; exit 1 }
if ($ev -lt 0) { $ev = $lines.Count }
$lines[$ep]
$parallel = Get-Problems $bp $ep
$vanilla = if ($bv -ge 0) { Get-Problems $bv $ev } else { @{} }
$lines | Select-String '\[MultiCore fuzz\] [^:]+: placed' | ForEach-Object { ($_.Line -replace '^.*\]: ', '') }
""
"Distinct problems: MultiCore on = {0}, MultiCore off = {1}" -f $parallel.Count, $vanilla.Count
""
"==== Broken classes (always suspect: MultiCore Universal rewrites classes in both halves of the run):"
$lines | Select-String -Pattern 'VerifyError|ClassFormatError|IncompatibleClassChangeError|NoClassDefFoundError|AbstractMethodError|NoSuchMethodError|NoSuchFieldError|IllegalAccessError' |
    ForEach-Object { ($_.Line -replace '^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\]: ', '') } | Sort-Object -Unique | Select-Object -First $Top
""
"==== Only with MultiCore on (candidates for MultiCore bugs):"
$parallel.GetEnumerator() | Where-Object { -not $vanilla.ContainsKey($_.Key) } | Sort-Object { -$_.Value.Count } | Select-Object -First $Top |
    ForEach-Object { "x{0,-5} [{1}] {2}" -f $_.Value.Count, $_.Value.Thread, $_.Key }
""
"==== In both runs (the mods' own problems), most frequent first:"
$parallel.GetEnumerator() | Where-Object { $vanilla.ContainsKey($_.Key) } | Sort-Object { -$_.Value.Count } | Select-Object -First $Top |
    ForEach-Object { "x{0,-5} {1}" -f $_.Value.Count, $_.Key }
""
"==== Only with MultiCore off:"
$vanilla.GetEnumerator() | Where-Object { -not $parallel.ContainsKey($_.Key) } | Sort-Object { -$_.Value.Count } | Select-Object -First $Top |
    ForEach-Object { "x{0,-5} {1}" -f $_.Value.Count, $_.Key }
