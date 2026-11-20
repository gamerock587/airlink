param([string]$Serial)
$ErrorActionPreference='Stop'
$sdk=if($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/sdk' }
$adb=Join-Path $sdk 'platform-tools/adb.exe'
if(!(Test-Path $adb)) { throw 'Set ANDROID_HOME to your Android SDK with adb installed.' }
if(!$Serial) {
    $devices=@(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if($devices.Count -ne 1) { throw 'Connect and authorize one Android phone, or provide -Serial.' }
    $Serial=$devices[0]
}
# These settings apply only to AirLink, never to other apps or global power policy.
& $adb -s $Serial shell cmd appops set com.airlink.audio RUN_ANY_IN_BACKGROUND allow
& $adb -s $Serial shell cmd appops set com.airlink.audio RUN_IN_BACKGROUND allow
& $adb -s $Serial shell cmd deviceidle whitelist +com.airlink.audio
& $adb -s $Serial shell am set-standby-bucket com.airlink.audio active
if($LASTEXITCODE -ne 0) { throw 'Android background setup did not complete.' }
& $adb -s $Serial shell cmd appops get com.airlink.audio RUN_ANY_IN_BACKGROUND
Write-Host 'AirLink background running is allowed. Reopen AirLink so it can restore its foreground service.'
