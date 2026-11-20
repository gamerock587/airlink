# Run this if Windows Firewall blocks incoming Android discovery or connection requests.
# The rule is scoped to AirLink, UDP, the Private profile and the local subnet.
#Requires -RunAsAdministrator
param([string]$AppPath = (Join-Path $PSScriptRoot 'AirLink.exe'))
$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $AppPath).Path
if ([IO.Path]::GetFileName($resolved) -ne 'AirLink.exe') { throw 'Select the published AirLink.exe.' }
$ruleName = 'AirLink-Local-Audio'
Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name $ruleName -DisplayName 'AirLink Windows audio server (Private LAN only)' -Direction Inbound -Action Allow -Program $resolved -Protocol UDP -LocalPort 45832,45833 -Profile Private -RemoteAddress LocalSubnet | Out-Null
Write-Host 'Android may discover and connect to the AirLink Windows server on your private local network.'
Write-Host 'To remove this rule: Remove-NetFirewallRule -Name AirLink-Local-Audio'
