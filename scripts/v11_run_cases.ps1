param(
    [Parameter(Mandatory = $true)][string[]]$Methods,
    [string]$Run = 'r6',
    [hashtable]$InstrumentationArgs = @{}
)
$ErrorActionPreference = 'Stop'
$v11Adb = 'C:\Users\16562\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$v11Root = Split-Path -Parent $PSScriptRoot
$v11Evidence = Join-Path $v11Root 'output/v1.1'
New-Item -ItemType Directory -Force -Path $v11Evidence | Out-Null
foreach ($v11Method in $Methods) {
    if ($v11Method -notmatch '^[A-Za-z][A-Za-z0-9]+$') { throw 'Invalid method name' }
    $v11Args = @('-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-e', 'class', "cn.kangyang.workbook.V11AcceptanceTest#$v11Method")
    foreach ($v11Key in $InstrumentationArgs.Keys) { $v11Args += @('-e', $v11Key, [string]$InstrumentationArgs[$v11Key]) }
    $v11Args += 'cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner'
    $v11Log = Join-Path $v11Evidence "$v11Method-$Run.log"
    Write-Output "Running $v11Method ($Run) on emulator-5554"
    $v11Output = & $v11Adb @v11Args 2>&1
    $v11Output | Set-Content -LiteralPath $v11Log -Encoding utf8
    $v11Output | Select-Object -Last 16
    if (($v11Output -join "`n") -notmatch '(?m)^OK \(\d+ tests?\)') {
        throw "Actual instrumentation did not pass: $v11Log"
    }
}
