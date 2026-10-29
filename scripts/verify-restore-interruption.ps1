param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOut=Join-Path $taskRoot 'output\verification'
$adb=(Get-Command adb).Source
$package='cn.kangyang.workbook.qa'
$oldMarker=(& $adb -s $Serial shell run-as $package cat files/restore-interruption-ready.txt 2>$null | Out-String).Trim()
$proc=Start-Process -FilePath $adb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','class','cn.kangyang.workbook.RestoreProcessRecoveryTest#prepareInterruption','-e','interruptRestore','true',"$package.test/androidx.test.runner.AndroidJUnitRunner") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskOut 'e11-interrupt-prepare.log') -RedirectStandardError (Join-Path $taskOut 'e11-interrupt-prepare-stderr.log')
$ready=$null
for($attempt=0;$attempt -lt 25;$attempt++) {
    Start-Sleep -Seconds 2
    $marker=(& $adb -s $Serial shell run-as $package cat files/restore-interruption-ready.txt 2>$null | Out-String).Trim()
    if($marker.StartsWith('files_staged:') -and $marker -ne $oldMarker){$ready=$marker;break}
    if($proc.HasExited){throw 'Preparation exited before the restore checkpoint. Inspect the preparation log.'}
}
if(!$ready){throw 'No restore checkpoint observed within 50 seconds; interruption was not performed.'}
$before=(& $adb -s $Serial shell pidof $package | Out-String).Trim()
& $adb -s $Serial shell am force-stop $package
$after=(& $adb -s $Serial shell pidof $package | Out-String).Trim()
@{checkpoint=$ready;beforePid=$before;afterPid=$after;operation='force-stop only cn.kangyang.workbook.qa after immutable files staged, before database commit'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut 'e11-process-interruption.json')
& $adb -s $Serial shell am instrument -w -e class cn.kangyang.workbook.RestoreProcessRecoveryTest#verifyAfterInterruption -e interruptRestore true "$package.test/androidx.test.runner.AndroidJUnitRunner" | Set-Content -LiteralPath (Join-Path $taskOut 'e11-interrupt-verify.log')
& $adb -s $Serial shell run-as $package cat files/restore-interruption-verified.txt | Set-Content -LiteralPath (Join-Path $taskOut 'e11-interrupt-result.txt')
Get-Content -LiteralPath (Join-Path $taskOut 'e11-interrupt-result.txt')
