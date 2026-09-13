$ErrorActionPreference = 'Stop'
Write-Host '==============================================' -ForegroundColor Cyan
Write-Host ' Xbox Phone Controller - Preparar este PC'
Write-Host '==============================================' -ForegroundColor Cyan

$vigem = Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -like '*Virtual Gamepad Emulation Bus*' }
if (-not $vigem) {
    Write-Host 'Instalando ViGEmBus 1.22.0 via winget...' -ForegroundColor Yellow
    winget install --id ViGEm.ViGEmBus --version 1.22.0 --accept-package-agreements --accept-source-agreements
} else {
    Write-Host 'ViGEmBus já encontrado.' -ForegroundColor Green
}

$rule = Get-NetFirewallRule -DisplayName 'Xbox Phone Controller UDP 45990' -ErrorAction SilentlyContinue
if (-not $rule) {
    New-NetFirewallRule -DisplayName 'Xbox Phone Controller UDP 45990' -Direction Inbound -Protocol UDP -LocalPort 45990 -Action Allow | Out-Null
    Write-Host 'Regra de firewall criada.' -ForegroundColor Green
}

Write-Host ''
Write-Host 'Preparação concluída.' -ForegroundColor Green
Write-Host 'Agora execute XboxPhoneReceiver.exe e informe no celular o IP exibido.'
Pause
