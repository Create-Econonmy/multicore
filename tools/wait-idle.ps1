# Waits until nobody has used the keyboard or mouse for -Minutes (default 20), then exits.
# Used before heavy (multi-GB) test runs so they never slow the computer down while someone works on it.
param([int]$Minutes = 20)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class IdleTime {
    [StructLayout(LayoutKind.Sequential)] struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
    [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
    public static uint Seconds() {
        LASTINPUTINFO i = new LASTINPUTINFO(); i.cbSize = (uint)Marshal.SizeOf(i);
        GetLastInputInfo(ref i);
        return ((uint)Environment.TickCount - i.dwTime) / 1000;
    }
}
'@
while ([IdleTime]::Seconds() -lt $Minutes * 60) { Start-Sleep -Seconds 30 }
Write-Output "idle for $Minutes minutes"
