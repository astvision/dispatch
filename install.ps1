# Installs Dispatch for the current user on Windows: builds it from source and puts `dispatch` on your PATH.
#
#   irm https://raw.githubusercontent.com/astvision/dispatch/main/install.ps1 | iex
#
# While the repository is private, fetch it through the GitHub CLI instead:
#
#   gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.ps1 | Out-String | iex
#
# Needs Git for Windows and Java 25 or later; works in Windows PowerShell 5.1 and PowerShell 7. Settings: DISPATCH_REF
# (branch or tag, default main), DISPATCH_HOME (where Dispatch goes, default %LOCALAPPDATA%\Programs\Dispatch).
$ErrorActionPreference = "Stop"

$repo = if ($env:DISPATCH_REPO) { $env:DISPATCH_REPO } else { "astvision/dispatch" }
$ref = if ($env:DISPATCH_REF) { $env:DISPATCH_REF } else { "main" }
$target = if ($env:DISPATCH_HOME) { $env:DISPATCH_HOME } else { Join-Path $env:LOCALAPPDATA "Programs\Dispatch" }

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

# Runs a native command whose stderr is expected; Windows PowerShell 5.1 would stop on it otherwise.
function Quietly([scriptblock] $command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $command } finally { $ErrorActionPreference = $previous }
}

foreach ($needed in @(@("git", "install Git for Windows from https://git-scm.com"), @("java", "install Java 25 or later, e.g. Temurin from https://adoptium.net"))) {
    if (-not (Get-Command $needed[0] -ErrorAction SilentlyContinue)) { throw "$($needed[0]) is needed: $($needed[1])" }
}
$settings = Quietly { java -XshowSettings:properties -version 2>&1 | Out-String }
$match = [regex]::Match($settings, "java\.specification\.version = (\d+)")
$version = if ($match.Success) { [int]$match.Groups[1].Value } else { 0 }
if ($version -lt 25) { throw "Java 25 or later is needed; java on your PATH is $version" }

# Build from this checkout when run from one, otherwise from a fresh clone.
$source = $null
$temporary = $null
$pom = if ($PSScriptRoot) { Join-Path $PSScriptRoot "pom.xml" } else { $null }
if ($pom -and (Test-Path (Join-Path $PSScriptRoot "bin\dispatch.cmd")) -and (Select-String -Quiet -Path $pom -Pattern "<artifactId>dispatch</artifactId>")) {
    $source = $PSScriptRoot
} else {
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("dispatch-" + [guid]::NewGuid())
    $source = $temporary
    Step "Cloning $repo ($ref)"
    $viaGh = $false
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        Quietly { gh auth status *> $null }
        $viaGh = ($LASTEXITCODE -eq 0)
    }
    if ($viaGh) {
        Quietly { gh repo clone $repo $source -- --quiet --depth 1 --branch $ref }
    } else {
        Quietly { git clone --quiet --depth 1 --branch $ref "https://github.com/$repo.git" $source }
    }
    if ($LASTEXITCODE -ne 0) { throw "cloning $repo failed" }
}

try {
    Step "Building Dispatch (the first build also downloads Maven and the libraries)"
    Push-Location $source
    try {
        Quietly { .\mvnw.cmd --quiet --batch-mode -DskipTests package }
        if ($LASTEXITCODE -ne 0) { throw "the build failed" }
    } finally {
        Pop-Location
    }
    $jar = Get-ChildItem (Join-Path $source "target") -Filter "dispatch-*.jar" | Where-Object { $_.Name -notlike "original-*" } | Select-Object -First 1
    if (-not $jar) { throw "the build made no jar in $source\target" }

    Step "Installing into $target"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item $jar.FullName (Join-Path $target "dispatch.jar") -Force
    Copy-Item (Join-Path $source "bin\dispatch.cmd") (Join-Path $target "dispatch.cmd") -Force

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @(if ($userPath) { $userPath -split ";" | Where-Object { $_ } })
    if ($entries -notcontains $target) {
        [Environment]::SetEnvironmentVariable("Path", (($entries + $target) -join ";"), "User")
        $env:Path = "$env:Path;$target"
        Step "Added $target to your PATH; open a new terminal to use dispatch there"
    }
    Step "Installed. Set it up with: dispatch init"
} finally {
    if ($temporary) { Remove-Item -Recurse -Force $temporary -ErrorAction SilentlyContinue }
}
