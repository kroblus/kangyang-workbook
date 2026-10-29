param([string]$Serial='emulator-5554',[string]$RunLabel='')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOut=Join-Path $taskRoot 'output\verification'
$taskOriginalSize=(& adb -s $Serial shell wm size | Out-String).Trim()
$taskOriginalDensity=(& adb -s $Serial shell wm density | Out-String).Trim()
$taskOriginalFont=(& adb -s $Serial shell settings get system font_scale | Out-String).Trim()
@{size=$taskOriginalSize;density=$taskOriginalDensity;fontScale=$taskOriginalFont} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut 'e14-original-settings.json')
$taskConfigurations=@(
    @{width=320;pixels='640x1422'},
    @{width=360;pixels='720x1600'},
    @{width=430;pixels='860x1910'}
)
try {
    foreach($taskConfiguration in $taskConfigurations) {
        foreach($taskFont in @('1.0','1.5')) {
            $taskLabel="$($taskConfiguration.width)dp-font$taskFont"
            Write-Output "START $taskLabel"
            & adb -s $Serial shell wm size $taskConfiguration.pixels
            & adb -s $Serial shell wm density 320
            & adb -s $Serial shell settings put system font_scale $taskFont
            Start-Sleep -Seconds 2
            & adb -s $Serial shell am instrument -w -e class cn.kangyang.workbook.LayoutFlowTest#captureConfiguredLayoutAndKeyboardSave -e layoutLabel $taskLabel -e expectedWidthDp $taskConfiguration.width -e expectedFontScale $taskFont cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner > (Join-Path $taskOut "e14-$taskLabel$RunLabel.log") 2>&1
            Get-Content -LiteralPath (Join-Path $taskOut "e14-$taskLabel$RunLabel.log") -Tail 8
        }
    }
} finally {
    if($taskOriginalSize -match 'Override size: (\d+x\d+)') { & adb -s $Serial shell wm size $Matches[1] } else { & adb -s $Serial shell wm size reset }
    if($taskOriginalDensity -match 'Override density: (\d+)') { & adb -s $Serial shell wm density $Matches[1] } else { & adb -s $Serial shell wm density reset }
    if($taskOriginalFont -eq 'null') { & adb -s $Serial shell settings delete system font_scale } else { & adb -s $Serial shell settings put system font_scale $taskOriginalFont }
    @{size=(& adb -s $Serial shell wm size | Out-String).Trim();density=(& adb -s $Serial shell wm density | Out-String).Trim();fontScale=(& adb -s $Serial shell settings get system font_scale | Out-String).Trim()} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut 'e14-restored-settings.json')
}
