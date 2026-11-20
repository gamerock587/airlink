param([int]$SoakSeconds=0)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
$serverProcess = $null
try {
    & dotnet build tests/AirLink.Tests.csproj -c Release --nologo
    if($LASTEXITCODE -ne 0) { throw 'Windows integration host build failed.' }
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
    $javac = if ($javaBin) { Join-Path $javaBin 'javac.exe' } else { 'javac' }
    $java = if ($javaBin) { Join-Path $javaBin 'java.exe' } else { 'java' }
    New-Item -ItemType Directory -Force verification/java,verification/test-libs | Out-Null
    $jsonJar = 'verification/test-libs/json-20240303.jar'
    if (!(Test-Path $jsonJar)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jsonJar }
    if ((Get-FileHash $jsonJar).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Test JSON library hash mismatch.' }
    & $javac -cp $jsonJar -d verification/java android/app/src/main/java/com/airlink/audio/Protocol.java android/app/src/main/java/com/airlink/audio/JitterBuffer.java android/app/src/main/java/com/airlink/audio/ServerDiscovery.java android/app/src/main/java/com/airlink/audio/ClientPairing.java android/app/src/main/java/com/airlink/audio/ClientRecovery.java tests/ProtocolCheck.java tests/JavaClientCheck.java tests/PairingFailureCheck.java tests/LongStreamCheck.java
    if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed.' }
    $classPath = "verification/java$([IO.Path]::PathSeparator)$jsonJar"
    & $java -cp $classPath com.airlink.audio.ProtocolCheck verification
    if ($LASTEXITCODE -ne 0) { throw 'Java protocol/jitter checks failed.' }
    & $java -cp $classPath com.airlink.audio.PairingFailureCheck
    if ($LASTEXITCODE -ne 0) { throw 'Client timeout/progress checks failed.' }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command dotnet).Source
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add((Join-Path $root 'tests/bin/Release/net8.0-windows/AirLink.Tests.dll'))
    $startInfo.ArgumentList.Add('--java-server')
    if($SoakSeconds -gt 0) { $startInfo.ArgumentList.Add('--soak') }
    $serverProcess = [Diagnostics.Process]::Start($startInfo)
    $ready = $serverProcess.StandardOutput.ReadLineAsync()
    if (!$ready.Wait(10000) -or $ready.Result -notmatch '^JAVA_SERVER_READY ([0-9]+)$') { throw 'Windows integration server could not start.' }
    $testPort=$Matches[1]
    $output = $serverProcess.StandardOutput.ReadToEndAsync()
    $errors = $serverProcess.StandardError.ReadToEndAsync()
    if($SoakSeconds -gt 0) { & $java -cp $classPath com.airlink.audio.LongStreamCheck $testPort $SoakSeconds }
    else { & $java -cp $classPath com.airlink.audio.JavaClientCheck $testPort }
    if ($LASTEXITCODE -ne 0) { throw 'Android-to-Windows integration failed.' }
    if (!$serverProcess.WaitForExit(10000)) { throw 'Windows integration server did not finish.' }
    Write-Host $output.Result
    if ($serverProcess.ExitCode -ne 0) { throw $errors.Result }
} finally {
    if ($serverProcess -and !$serverProcess.HasExited) { $serverProcess.Kill() }
    Pop-Location
}
