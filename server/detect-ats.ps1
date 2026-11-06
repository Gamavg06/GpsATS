# detect-ats.ps1: Detección dinámica y robusta de la instalación de American Truck Simulator
[CmdletBinding()]
param()

$defaultPath = "C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator"
$candidatePaths = [System.Collections.Generic.List[string]]::new()
$candidatePaths.Add($defaultPath)

try {
    # 1. Consultar registro de Windows para la ruta de instalación de Steam
    $steamPath = (Get-ItemProperty -Path "HKCU:\Software\Valve\Steam" -Name "SteamPath" -ErrorAction SilentlyContinue).SteamPath
    if (-not $steamPath) {
        $steamPath = (Get-ItemProperty -Path "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam" -Name "InstallPath" -ErrorAction SilentlyContinue).InstallPath
    }

    if ($steamPath -and (Test-Path $steamPath)) {
        # Agregar biblioteca principal de Steam
        $mainLib = Join-Path $steamPath "steamapps\common\American Truck Simulator"
        if (-not $candidatePaths.Contains($mainLib)) { $candidatePaths.Add($mainLib) }

        # 2. Parsear libraryfolders.vdf para bibliotecas en otros discos (D:, E:, etc.)
        $vdfPath = Join-Path $steamPath "steamapps\libraryfolders.vdf"
        if (Test-Path $vdfPath) {
            $vdfLines = Get-Content $vdfPath -ErrorAction SilentlyContinue
            foreach ($line in $vdfLines) {
                if ($line -match '"path"\s+"([^"]+)"') {
                    $libDir = $matches[1].Replace('\\', '\')
                    $candidate = Join-Path $libDir "steamapps\common\American Truck Simulator"
                    if (-not $candidatePaths.Contains($candidate)) {
                        $candidatePaths.Add($candidate)
                    }
                }
            }
        }
    }
}
catch {
    # Continuar con rutas candidatas acumuladas
}

# 3. Comprobar existencia real del binario amtrucks.exe
$foundPath = $null
foreach ($path in $candidatePaths) {
    $exe = Join-Path $path "bin\win_x64\amtrucks.exe"
    if (Test-Path $exe) {
        $foundPath = $path
        break
    }
}

if ($foundPath) {
    Write-Output $foundPath
} else {
    Write-Output $defaultPath
}
