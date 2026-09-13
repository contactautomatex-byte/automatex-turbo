$ErrorActionPreference = 'Stop'

# Este instalador precisa de privilegios de Administrador para criar a regra
# de firewall e, quando necessario, instalar o ViGEmBus.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host 'Solicitando permissao de Administrador...' -ForegroundColor Yellow
    $args = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"' + $PSCommandPath + '"')
    )
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $args
    exit
}

Write-Host '==============================================' -ForegroundColor Cyan
Write-Host ' Xbox Phone Controller - Preparar este PC'
Write-Host '==============================================' -ForegroundColor Cyan
Write-Host 'Executando como Administrador.' -ForegroundColor Green
Write-Host ''

$vigem = Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -like '*Virtual Gamepad Emulation Bus*' }
if (-not $vigem) {
    Write-Host 'Instalando ViGEmBus 1.22.0 via winget...' -ForegroundColor Yellow
    winget install --id ViGEm.ViGEmBus --version 1.22.0 --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao instalar o ViGEmBus. Codigo: $LASTEXITCODE"
    }
} else {
    Write-Host 'ViGEmBus ja encontrado.' -ForegroundColor Green
}

$ruleName = 'Xbox Phone Controller UDP 45990'
$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $rule) {
    Write-Host 'Criando regra de firewall UDP 45990...' -ForegroundColor Yellow
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol UDP -LocalPort 45990 -Action Allow -Profile Any | Out-Null
}

$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $rule) {
    throw 'Nao foi possivel confirmar a regra de firewall.'
}
Write-Host 'Regra de firewall confirmada.' -ForegroundColor Green

Write-Host ''
Write-Host 'Preparacao concluida com sucesso.' -ForegroundColor Green
Write-Host 'Agora execute XboxPhoneReceiver.exe e informe no celular o IP exibido.'
Write-Host ''
Read-Host 'Pressione ENTER para fechar'
