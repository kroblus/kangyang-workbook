param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('Begin','End')]
    [string]$Mode,
    [string]$Serial,
    [ValidatePattern('^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$')]
    [string]$Package='cn.kangyang.workbook.qa.perf',
    [string]$OutputDir
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
if(!$OutputDir){$OutputDir=Join-Path $taskRoot 'output\performance'}

# Use an existing adb; do not install an SDK or create an emulator.
$taskAdbCommand=Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if($taskAdbCommand){
    $taskAdb=$taskAdbCommand.Source
} else {
    $taskSdkRoots=@($env:ANDROID_SDK_ROOT,$env:ANDROID_HOME)
    if($env:LOCALAPPDATA){$taskSdkRoots+=Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
    $taskAdb=$taskSdkRoots | Where-Object {$_} | ForEach-Object {
        Join-Path $_ 'platform-tools\adb.exe'
    } | Where-Object {Test-Path -LiteralPath $_ -PathType Leaf} | Select-Object -First 1
}
if(!$taskAdb){throw 'Existing adb not found. Add platform-tools to PATH or set ANDROID_SDK_ROOT / ANDROID_HOME.'}

function Invoke-TaskAdb([string[]]$AdbArguments){
    # Windows PowerShell can wrap native stderr in ErrorRecord objects.
    # Preserve it, then judge the native exit code instead of stderr alone.
    $ErrorActionPreference='Continue'
    $taskLines=@(& $taskAdb @AdbArguments 2>&1 | ForEach-Object {$_.ToString()})
    $taskExitCode=$LASTEXITCODE
    return [pscustomobject]@{Lines=$taskLines;ExitCode=$taskExitCode}
}

$taskDevices=Invoke-TaskAdb -AdbArguments @('devices','-l')
if($taskDevices.ExitCode -ne 0){throw "adb devices failed ($($taskDevices.ExitCode)): $($taskDevices.Lines -join ' ')"}
$taskOnline=@($taskDevices.Lines | ForEach-Object {
    if($_ -match '^(\S+)\s+device(?:\s|$)'){$Matches[1]}
})
if(!$Serial){
    $taskEmulators=@($taskOnline | Where-Object {$_ -match '^emulator-\d+$'})
    if($taskEmulators.Count -eq 0){throw 'No available emulator found. Start the existing emulator, or explicitly select an online device with -Serial.'}
    if($taskEmulators.Count -gt 1){throw "Multiple emulators are available: $($taskEmulators -join ', '). Specify -Serial."}
    $Serial=$taskEmulators[0]
}
if($Serial -notin $taskOnline){
    throw "Serial '$Serial' is not online in adb devices -l. Select a device in the 'device' state."
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$taskOut=(Resolve-Path -LiteralPath $OutputDir).ProviderPath
$taskStamp=Get-Date -Format 'yyyyMMdd-HHmmssfff'
$taskPrefix="$taskStamp-$($Mode.ToLowerInvariant())"
$taskContextPath=Join-Path $taskOut "$taskPrefix-context.txt"
$taskIntervalPath=Join-Path $taskOut 'last-begin.json'
$taskContext=@(
    'Manual interaction interval; this is not an automated benchmark or a PASS/FAIL gate.',
    'Video recording FPS is not app FPS. No FPS is calculated by this script.',
    "Mode: $Mode",
    "Capture started (UTC): $([DateTime]::UtcNow.ToString('o'))",
    "Serial: $Serial",
    "Package: $Package",
    "adb: $taskAdb"
)
$taskContext | Set-Content -LiteralPath $taskContextPath -Encoding UTF8

function Save-TaskAdbDump([string]$Name,[string[]]$AdbArguments,[switch]$SeparateFile){
    $taskResult=Invoke-TaskAdb -AdbArguments $AdbArguments
    "`n[$Name] adb $($AdbArguments -join ' ') -> exit $($taskResult.ExitCode)" |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    $taskPath=$taskContextPath
    if($SeparateFile){
        $taskPath=Join-Path $taskOut "$taskPrefix-$Name.txt"
        ($taskResult.Lines -join [Environment]::NewLine) | Set-Content -LiteralPath $taskPath -Encoding UTF8
        "Raw file: $([IO.Path]::GetFileName($taskPath))" | Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    } else {
        ($taskResult.Lines -join [Environment]::NewLine) | Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    }
    if($taskResult.ExitCode -ne 0){Write-Warning "adb $($AdbArguments -join ' ') returned $($taskResult.ExitCode). Raw output: $taskPath"}
    return $taskResult
}

Write-Host "Manual capture: $Mode | $Serial | $Package"
if($Mode -eq 'Begin'){
    ($taskDevices.Lines -join [Environment]::NewLine) |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    $null=Save-TaskAdbDump -Name 'adb-version' -AdbArguments @('version')
    foreach($taskProperty in @('ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.fingerprint')){
        $taskValue=Invoke-TaskAdb -AdbArguments @('-s',$Serial,'shell','getprop',$taskProperty)
        "$taskProperty (exit $($taskValue.ExitCode)): $($taskValue.Lines -join ' ')" |
            Add-Content -LiteralPath $taskContextPath -Encoding UTF8
        if($taskValue.ExitCode -ne 0){Write-Warning "Could not read $taskProperty; see context file."}
    }
    $null=Save-TaskAdbDump -Name 'screen-size' -AdbArguments @('-s',$Serial,'shell','wm','size')
    $null=Save-TaskAdbDump -Name 'screen-density' -AdbArguments @('-s',$Serial,'shell','wm','density')
    'Display mode / refresh information below is device-reported text, not measured app FPS. Supported modes are not necessarily the active mode.' |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    $null=Save-TaskAdbDump -Name 'display' -AdbArguments @('-s',$Serial,'shell','dumpsys','display')
    'Package version / build flags below are reported text only; absent flags do not establish a build variant or R8 setting.' |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    $taskPackageInfo=Save-TaskAdbDump -Name 'package' -AdbArguments @('-s',$Serial,'shell','dumpsys','package',$Package)
    if($taskPackageInfo.ExitCode -ne 0 -or !($taskPackageInfo.Lines -match 'versionCode=')){
        throw "Could not read the installed package '$Package'. Inspect the package dump; no measurement reset was performed."
    }
    $taskPid=Save-TaskAdbDump -Name 'pid' -AdbArguments @('-s',$Serial,'shell','pidof',$Package)
    if($taskPid.ExitCode -ne 0 -or !($taskPid.Lines -match '^\d+( \d+)*$')){
        throw 'Open the installed app first, then run Begin again so gfxinfo can reset the active process.'
    }
    $taskReset=Save-TaskAdbDump -Name 'gfxinfo-reset' -AdbArguments @('-s',$Serial,'shell','dumpsys','gfxinfo',$Package,'reset')
    if($taskReset.ExitCode -ne 0){throw 'gfxinfo reset failed. Raw output was saved; Begin was not completed.'}
    [ordered]@{
        resetCommandFinishedUtc=[DateTime]::UtcNow.ToString('o')
        serial=$Serial
        package=$Package
        contextFile=[IO.Path]::GetFileName($taskContextPath)
    } | ConvertTo-Json | Set-Content -LiteralPath $taskIntervalPath -Encoding UTF8
    Write-Host 'Begin recorded and gfxinfo reset requested. Now perform the target interactions manually.'
    Write-Host 'Then run End with the same Serial, Package and OutputDir. No app launch, animation setting change or app-data change was performed.'
} else {
    if(Test-Path -LiteralPath $taskIntervalPath){
        $taskBegin=Get-Content -LiteralPath $taskIntervalPath -Raw | ConvertFrom-Json
        if($taskBegin.serial -eq $Serial -and $taskBegin.package -eq $Package){
            "Matching Begin reset command finished (UTC): $($taskBegin.resetCommandFinishedUtc); context: $($taskBegin.contextFile)" |
                Add-Content -LiteralPath $taskContextPath -Encoding UTF8
        } else {
            Write-Warning 'The last Begin used a different device or package. These are current counters, not a matched Begin/End interval.'
            'No matching Begin: device/package mismatch.' | Add-Content -LiteralPath $taskContextPath -Encoding UTF8
        }
    } else {
        Write-Warning 'No Begin metadata exists in OutputDir. Current counters will be saved, but a freshly reset interval cannot be assumed.'
        'No Begin metadata available.' | Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    }
    $taskGfx=Save-TaskAdbDump -Name 'gfxinfo-framestats' -SeparateFile -AdbArguments @('-s',$Serial,'shell','dumpsys','gfxinfo',$Package,'framestats')
    $taskMemory=Save-TaskAdbDump -Name 'meminfo' -SeparateFile -AdbArguments @('-s',$Serial,'shell','dumpsys','meminfo',$Package)
    $null=Save-TaskAdbDump -Name 'pid' -AdbArguments @('-s',$Serial,'shell','pidof',$Package)
    $taskSummary=@($taskGfx.Lines | Where-Object {
        $_ -match '(?i)(Graphics info|Stats since|Total frames rendered|Janky frames|percentile|Number (Missed|High|Slow|Frame)|HISTOGRAM)'
    })
    $taskSummaryPath=Join-Path $taskOut "$taskPrefix-janky-summary.txt"
    if($taskSummary.Count -gt 0){
        ($taskSummary -join [Environment]::NewLine) | Set-Content -LiteralPath $taskSummaryPath -Encoding UTF8
        Write-Host 'Device-reported gfxinfo summary (verbatim, no threshold applied):'
        $taskSummary | ForEach-Object {Write-Host $_}
    } else {
        'No matching summary lines were reported. See the complete gfxinfo output; this does not mean zero janky frames.' |
            Set-Content -LiteralPath $taskSummaryPath -Encoding UTF8
        Write-Warning 'gfxinfo did not provide the expected summary lines. No performance conclusion is inferred.'
    }
    "Capture finished (UTC): $([DateTime]::UtcNow.ToString('o'))" | Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    'gfxinfo counters belong to the reported process lifetime; process restart or another reset can break the manual interval. Compare the raw Begin/End PID and gfxinfo output.' |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    'meminfo is a post-interaction memory snapshot, not peak memory. No CPU throttling or slow-device simulation is performed.' |
        Add-Content -LiteralPath $taskContextPath -Encoding UTF8
    if($taskGfx.ExitCode -ne 0 -or $taskMemory.ExitCode -ne 0){
        throw "One or more captures failed. Partial raw results were saved in '$taskOut'; inspect their exit codes and output."
    }
    Write-Host 'End captured raw gfxinfo framestats and a post-interaction meminfo snapshot. This is a manual interval, not recording FPS, peak memory or a benchmark score.'
}
Write-Host "Context: $taskContextPath"
Write-Host "Output: $taskOut"
