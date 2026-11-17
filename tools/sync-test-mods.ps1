# Copies the modpack's jars into the local test server folders (run/gametest/mods and run/server/mods),
# and copies the jars MultiCore has compat code for into libs/ for compiling.
# Nothing is ever written to the modpack instance or the real server.
#
# Default: the CurseForge instance minus client-only mods (tools/client-only-mods.txt).
# -ServerList: exactly the jars in tools/server-mods.txt (the real server's mods folder), looked up in the instance and
# in -Extra folders. Jars that cannot be found locally, and jars that stop the server from starting, are reported.
param(
    [string]$Instance = "C:\Users\luket\curseforge\minecraft\Instances\Create 3.1 test_",
    [string[]]$Targets = @("run\gametest\mods", "run\server\mods"),
    [switch]$ServerList,
    # Which list -ServerList reads (tools/server-mods.txt = the snapshot of the real server from 2026-09-30).
    [string]$List = (Join-Path $PSScriptRoot "server-mods.txt"),
    [string[]]$Extra = @("$env:USERPROFILE\Downloads", "$env:USERPROFILE\curseforge\minecraft\Instances\Create Economy 2.0\mods"),
    # Jar name prefixes left out even with -ServerList because the pack cannot start with them (reason in the file).
    [string[]]$Unbootable = @("ScalableLux")
)
$root = Split-Path -Parent $PSScriptRoot
if ($ServerList) {
    $wanted = Get-Content $List | Where-Object { $_ -and -not $_.StartsWith("#") }
    $search = @((Join-Path $Instance "mods")) + $Extra
    $jars = @()
    foreach ($name in $wanted) {
        if ($Unbootable | Where-Object { $name.StartsWith($_) }) {
            "left out (the pack cannot start with it): $name"
            continue
        }
        $found = $search | ForEach-Object { Join-Path $_ $name } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $found) {
            # Same mod, other version: take the newest local copy and say so.
            $stem = ($name -replace '[-_+ ]?v?\d[\w.+-]*\.jar$', '')
            $found = $search | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter "$stem*.jar" -ErrorAction SilentlyContinue } |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
            if ($found) { "NOT FOUND: $name -> using $(Split-Path -Leaf $found) instead" } else { "NOT FOUND: $name (not tested)" }
        }
        if ($found) { $jars += Get-Item -LiteralPath $found }
    }
} else {
    $exclude = Get-Content (Join-Path $PSScriptRoot "client-only-mods.txt") | Where-Object { $_ -and -not $_.StartsWith("#") }
    $jars = Get-ChildItem (Join-Path $Instance "mods") -Filter *.jar | Where-Object {
        $name = $_.Name
        -not ($exclude | Where-Object { $name.StartsWith($_) })
    }
}
foreach ($t in $Targets) {
    $dir = Join-Path $root $t
    New-Item -ItemType Directory -Force $dir | Out-Null
    Get-ChildItem $dir -Filter *.jar | Remove-Item -Force
    $jars | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $dir }
    "{0}: {1} jars" -f $t, $jars.Count
}
$libs = Join-Path $root "libs"
New-Item -ItemType Directory -Force $libs | Out-Null
$compat = @("sable-neoforge", "create-1.21.1", "kubejs-neoforge", "rhino-")
$jars | Where-Object { $n = $_.Name; $compat | Where-Object { $n.StartsWith($_) } } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $libs }
"libs: " + ((Get-ChildItem $libs -Filter *.jar) | ForEach-Object Name) -join ", "
