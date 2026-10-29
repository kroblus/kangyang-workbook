param(
    [string]$Serial='emulator-5554',
    [string]$RunLabel='final',
    [string]$Adb='C:\Users\16562\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOut=Join-Path $taskRoot 'output\v1.1'
New-Item -ItemType Directory -Path $taskOut -Force | Out-Null
if($Serial -notmatch '^emulator-\d+$'){throw 'This script is restricted to the explicitly selected emulator.'}
$taskOriginal=@{
    size=(& $Adb -s $Serial shell wm size | Out-String).Trim()
    density=(& $Adb -s $Serial shell wm density | Out-String).Trim()
    fontScale=(& $Adb -s $Serial shell settings get system font_scale | Out-String).Trim()
    rotation=(& $Adb -s $Serial shell wm user-rotation | Out-String).Trim()
}
$taskOriginal | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut "t11-$RunLabel-original-settings.json")
$taskResults=@()
$taskConfigurations=@(
    @{width=320;pixels='640x1422'},
    @{width=360;pixels='720x1600'},
    @{width=430;pixels='860x1910'}
)
function Invoke-LayoutCase([string]$TaskLabel,[string]$TaskWidth,[string]$TaskFont){
    $taskLog=Join-Path $taskOut "t11-$TaskLabel-$RunLabel.log"
    Write-Host "Running $TaskLabel on $Serial"
    & $Adb -s $Serial shell am instrument -w -e class cn.kangyang.workbook.V11AcceptanceTest#representativeWidthAndFontLayout -e layoutLabel $TaskLabel -e expectedWidthDp $TaskWidth -e expectedFontScale $TaskFont cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner *> $taskLog
    $taskPass=Select-String -LiteralPath $taskLog -Pattern '^OK \(1 test\)' -Quiet
    Write-Host "$TaskLabel : $taskPass"
    Get-Content -LiteralPath $taskLog -Tail 8 | ForEach-Object {Write-Host $_}
    return @{configuration=$TaskLabel;pass=$taskPass;log=$taskLog}
}
try {
    & $Adb -s $Serial shell wm user-rotation lock 0
    foreach($taskConfiguration in $taskConfigurations){
        foreach($taskFont in @('1.0','1.5')){
            $taskLabel="$($taskConfiguration.width)dp-font$taskFont"
            & $Adb -s $Serial shell wm size $taskConfiguration.pixels
            & $Adb -s $Serial shell wm density 320
            & $Adb -s $Serial shell settings put system font_scale $taskFont
            Start-Sleep -Seconds 2
            $taskResult=Invoke-LayoutCase $taskLabel $taskConfiguration.width $taskFont
            $taskResults+=$taskResult | Where-Object {$_ -is [hashtable]}
        }
    }
    # Rotate the same native emulator; these are real resource/orientation changes.
    & $Adb -s $Serial shell wm size 720x1600
    & $Adb -s $Serial shell wm density 320
    & $Adb -s $Serial shell wm user-rotation lock 1
    foreach($taskFont in @('1.0','1.5')){
        & $Adb -s $Serial shell settings put system font_scale $taskFont
        Start-Sleep -Seconds 2
        $taskResult=Invoke-LayoutCase "landscape-font$taskFont" '800' $taskFont
        $taskResults+=$taskResult | Where-Object {$_ -is [hashtable]}
    }
} finally {
    if($taskOriginal.size -match 'Override size: (\d+x\d+)'){& $Adb -s $Serial shell wm size $Matches[1]}else{& $Adb -s $Serial shell wm size reset}
    if($taskOriginal.density -match 'Override density: (\d+)'){& $Adb -s $Serial shell wm density $Matches[1]}else{& $Adb -s $Serial shell wm density reset}
    if($taskOriginal.fontScale -eq 'null'){& $Adb -s $Serial shell settings delete system font_scale}else{& $Adb -s $Serial shell settings put system font_scale $taskOriginal.fontScale}
    if($taskOriginal.rotation -match 'lock\s+(\d)'){& $Adb -s $Serial shell wm user-rotation lock $Matches[1]}else{& $Adb -s $Serial shell wm user-rotation free}
    @{
        size=(& $Adb -s $Serial shell wm size | Out-String).Trim()
        density=(& $Adb -s $Serial shell wm density | Out-String).Trim()
        fontScale=(& $Adb -s $Serial shell settings get system font_scale | Out-String).Trim()
        rotation=(& $Adb -s $Serial shell wm user-rotation | Out-String).Trim()
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut "t11-$RunLabel-restored-settings.json")
    $taskResults | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut "t11-$RunLabel-results.json")
}
if($taskResults.Count -ne 8 -or ($taskResults | Where-Object {-not $_.pass})){throw 'At least one layout scenario did not pass; inspect the individual logs.'}
