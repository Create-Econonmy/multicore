# Copies a mod list into the local test server folders (run/gametest/mods and run/server/mods), to test MultiCore
# Universal with real mods. Nothing is ever written to the modpack instance or to any real server.
# The MultiCore Universal jar (and, for GameTests, the unsafe test mod) is added by Gradle before each run.
#
# Default: the CurseForge instance minus client-only mods (tools/client-only-mods.txt).
# -ServerList: exactly the jars in tools/server-mods.txt, looked up in the instance and in -Extra folders.
# -ModsDir: any other mods folder (to try MultiCore Universal with a different pack).
param(
    [string]$Instance = "C:\Users\luket\curseforge\minecraft\Instances\Create 3.1 test_",
    [string]$ModsDir,
    [string[]]$Targets = @("run\gametest\mods", "run\server\mods"),
    [switch]$ServerList,
    [string[]]$Extra = @("$env:USERPROFILE\Downloads", "$env:USERPROFILE\curseforge\minecraft\Instances\Create Economy 2.0\mods"),
    # Jar name prefixes left out because the pack cannot start with them, and the old pack-specific MultiCore.
    [string[]]$Unbootable = @("ScalableLux", "multicore-")
)
$root = Split-Path -Parent $PSScriptRoot
$exclude = Get-Content (Join-Path $PSScriptRoot "client-only-mods.txt") | Where-Object { $_ -and -not $_.StartsWith("#") }
if ($ServerList) {
    $wanted = Get-Content (Join-Path $PSScriptRoot "server-mods.txt") | Where-Object { $_ -and -not $_.StartsWith("#") }
    $search = @((Join-Path $Instance "mods")) + $Extra
    $jars = @()
    foreach ($name in $wanted) {
        if ($Unbootable | Where-Object { $name.StartsWith($_) }) {
            "left out: $name"
            continue
        }
        $found = $search | ForEach-Object { Join-Path $_ $name } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $found) {
            $stem = ($name -replace '[-_+ ]?v?\d[\w.+-]*\.jar$', '')
            $found = $search | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter "$stem*.jar" -ErrorAction SilentlyContinue } |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
            if ($found) { "NOT FOUND: $name -> using $(Split-Path -Leaf $found) instead" } else { "NOT FOUND: $name (not tested)" }
        }
        if ($found) { $jars += Get-Item -LiteralPath $found }
    }
} else {
    $source = if ($ModsDir) { $ModsDir } else { Join-Path $Instance "mods" }
    $jars = Get-ChildItem -LiteralPath $source -Filter *.jar | Where-Object {
        $name = $_.Name
        -not ($exclude | Where-Object { $name.StartsWith($_) }) -and -not ($Unbootable | Where-Object { $name.StartsWith($_) })
    }
}
foreach ($t in $Targets) {
    $dir = Join-Path $root $t
    New-Item -ItemType Directory -Force $dir | Out-Null
    Get-ChildItem $dir -Filter *.jar | Remove-Item -Force
    $jars | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $dir }
    "{0}: {1} jars" -f $t, $jars.Count
}
