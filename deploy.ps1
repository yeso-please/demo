[CmdletBinding()]
param(
    [string]$ServerIp = '3.39.43.59',
    [string]$KeyPath = 'C:\Users\ysj18\Downloads\LightsailDefaultKey-ap-northeast-2.pem',
    [switch]$FirstDeploy
)

$ErrorActionPreference = 'Stop'
$ProjectDir = $PSScriptRoot
$SshUser = 'ubuntu'
$SshTarget = "${SshUser}@${ServerIp}"
$JarPath = Join-Path $ProjectDir 'build\libs\hidden-travel-0.0.1-SNAPSHOT.jar'
$DbPath = Join-Path $ProjectDir 'data\sumeun.mv.db'
$SecretPath = Join-Path $ProjectDir 'config\application-secret.yaml'
$UploadsPath = Join-Path $ProjectDir 'uploads'
$RemoteStage = "/home/$SshUser/travel-upload"

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File failed with exit code $LASTEXITCODE"
    }
}

if (!(Test-Path -LiteralPath $KeyPath)) { throw "SSH key not found: $KeyPath" }
if (!(Test-Path -LiteralPath $DbPath)) { throw "H2 database not found: $DbPath" }
if ($FirstDeploy -and !(Test-Path -LiteralPath $SecretPath)) { throw "Secret file not found: $SecretPath" }

Set-Location -LiteralPath $ProjectDir
Write-Host "[1/7] Building latest executable JAR"
Invoke-Checked '.\gradlew.bat' @('clean', 'bootJar')
if (!(Test-Path -LiteralPath $JarPath)) { throw "JAR not found: $JarPath" }

Write-Host "[2/7] Stopping local Java server on port 8080"
$projectBootRun = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
    Where-Object {
        $_.CommandLine -like '*hidden-travel*' -and
        $_.CommandLine -like '*bootRun*'
    }
foreach ($bootRun in $projectBootRun) {
    Stop-Process -Id $bootRun.ProcessId -Force -ErrorAction SilentlyContinue
}
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $listener) {
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction Stop
    if ($process.ProcessName -notin @('java', 'javaw')) {
        throw "Port 8080 is used by non-Java process: $($process.ProcessName)"
    }
    Stop-Process -Id $process.Id
    for ($i = 0; $i -lt 30; $i++) {
        if (!(Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) { throw 'Local Java server did not stop' }
}

Write-Host "[3/7] Confirming H2 is no longer changing"
$hash1 = (Get-FileHash -LiteralPath $DbPath -Algorithm SHA256).Hash
Start-Sleep -Seconds 2
$hash2 = (Get-FileHash -LiteralPath $DbPath -Algorithm SHA256).Hash
if ($hash1 -ne $hash2) { throw 'H2 file is still changing; a process may still be using it' }
Write-Host "Local H2 SHA256: $hash1"

Write-Host "[4/7] Preparing remote staging directory"
Invoke-Checked 'ssh' @('-i', $KeyPath, $SshTarget, "mkdir -p $RemoteStage")

Write-Host "[5/7] Uploading JAR"
Invoke-Checked 'scp' @('-i', $KeyPath, $JarPath, "${SshTarget}:${RemoteStage}/app.jar")
if ($FirstDeploy) {
    Write-Host "Uploading H2 database, uploads, and secret configuration"
    Invoke-Checked 'scp' @('-i', $KeyPath, $DbPath, "${SshTarget}:${RemoteStage}/sumeun.mv.db")
    Invoke-Checked 'scp' @('-i', $KeyPath, $SecretPath, "${SshTarget}:${RemoteStage}/application-secret.yaml")
    if (Test-Path -LiteralPath $UploadsPath) {
        Invoke-Checked 'scp' @('-i', $KeyPath, '-r', $UploadsPath, "${SshTarget}:${RemoteStage}/")
    }
}

Write-Host "[6/7] Running remote deployment"
$remoteMode = if ($FirstDeploy) { 'first' } else { 'update' }
Invoke-Checked 'scp' @('-i', $KeyPath, (Join-Path $ProjectDir 'deploy-server.sh'), "${SshTarget}:${RemoteStage}/deploy-server.sh")
Invoke-Checked 'ssh' @('-i', $KeyPath, $SshTarget, "chmod 700 $RemoteStage/deploy-server.sh && sudo bash $RemoteStage/deploy-server.sh $remoteMode")

Write-Host "[7/7] Checking public endpoint"
$response = Invoke-WebRequest -Uri "http://$ServerIp/" -Method Head -TimeoutSec 20
Write-Host "HTTP status: $($response.StatusCode)"
Write-Host "Deployment completed: http://$ServerIp/"
