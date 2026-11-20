param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (!$env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\sdk")) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\sdk" }
    if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK.' }
    if (!$SkipChecks) {
        & dotnet run --project tests/AirLink.Tests.csproj -c Release -- --settings
        if ($LASTEXITCODE -ne 0) { throw 'Saved settings checks failed.' }
        & dotnet run --project tests/AirLink.Tests.csproj -c Release -- verification
        if ($LASTEXITCODE -ne 0) { throw 'Windows checks failed.' }
        & ./scripts/Test-JavaClient.ps1
    }
    New-Item -ItemType Directory -Force dist | Out-Null
    New-Item -ItemType Directory -Force dist | Out-Null
    New-Item -ItemType Directory -Force dist | Out-Null
    Push-Location android
    try {
        & .\gradlew.bat :app:assembleDebug :app:lintDebug --console plain
        if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed.' }
    } finally { Pop-Location }
    & dotnet publish windows/AirLink.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist/AirLink-Windows-1.3.3 --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
    Copy-Item -LiteralPath android/app/build/outputs/apk/debug/app-debug.apk -Destination dist/AirLink-Android-1.4.1.apk -Force
    Copy-Item -LiteralPath scripts/Allow-LanAudio.ps1 -Destination dist/AirLink-Windows-1.3.3/Allow-LanAudio.ps1 -Force
    Copy-Item -LiteralPath README.md -Destination dist/START-HERE.md -Force
    Copy-Item -LiteralPath THIRD-PARTY-NOTICES.md -Destination dist/AirLink-Windows-1.3.3/THIRD-PARTY-NOTICES.md -Force
    $packageRoot = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES } else { Join-Path $env:USERPROFILE '.nuget/packages' }
    $runtime = Get-Content windows/bin/Release/net8.0-windows/win-x64/AirLink.runtimeconfig.json -Raw | ConvertFrom-Json
    foreach ($framework in $runtime.runtimeOptions.includedFrameworks) {
        $packageName = $framework.name.ToLowerInvariant() + '.runtime.win-x64'
        $packageDir = Join-Path $packageRoot "$packageName/$($framework.version)"
        foreach ($notice in (Get-ChildItem -LiteralPath $packageDir -File | Where-Object { $_.Name -like 'LICENSE*' -or $_.Name -like 'THIRD-PARTY-NOTICES*' })) {
            Copy-Item -LiteralPath $notice.FullName -Destination (Join-Path 'dist/AirLink-Windows-1.3.3' ($framework.name + '-' + $notice.Name)) -Force
        }
    }
    Compress-Archive -Path dist/AirLink-Windows-1.3.3/* -DestinationPath dist/AirLink-Windows-1.3.3-x64.zip -Force
    $hashes = Get-FileHash dist/AirLink-Android-1.4.1.apk,dist/AirLink-Windows-1.3.3/AirLink.exe,dist/AirLink-Windows-1.3.3-x64.zip
    $hashes | ForEach-Object { "$($_.Hash)  $([IO.Path]::GetFileName($_.Path))" } | Set-Content dist/SHA256SUMS.txt
    $hashes | Format-Table -AutoSize
} finally { Pop-Location }
