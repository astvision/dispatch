# Installs Dispatch for the current user on Windows: downloads the latest release (or builds it from source) and puts
# `dispatch` on your PATH.
#
#   irm https://raw.githubusercontent.com/astvision/dispatch/main/install.ps1 | iex
#
# While the repository is private, fetch it through the GitHub CLI instead:
#
#   gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.ps1 | Out-String | iex
#
# Needs Git for Windows (when building from source) and Java 25 or later; works in Windows PowerShell 5.1 and PowerShell 7.
# Settings: DISPATCH_REF (branch or tag, default main), DISPATCH_HOME (where Dispatch goes, default
# %LOCALAPPDATA%\Programs\Dispatch), DISPATCH_FROM_SOURCE=1 (build from source instead of downloading).
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

foreach ($needed in @(@("java", "install Java 25 or later, e.g. Temurin from https://adoptium.net"))) {
    if (-not (Get-Command $needed[0] -ErrorAction SilentlyContinue)) { throw "$($needed[0]) is needed: $($needed[1])" }
}
$settings = Quietly { java -XshowSettings:properties -version 2>&1 | Out-String }
$match = [regex]::Match($settings, "java\.specification\.version = (\d+)")
$version = if ($match.Success) { [int]$match.Groups[1].Value } else { 0 }
if ($version -lt 25) { throw "Java 25 or later is needed; java on your PATH is $version" }

# Downloads the release's jar and launcher into $dir and verifies both against SHA256SUMS; $false (after showing the
# download command's own error) when there is no release or the download otherwise fails.
function Get-Release($dir) {
    $release = if ($ref -like "v*") { $ref } else { "latest" }
    $viaGh = $false
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        Quietly { gh auth status *> $null }
        $viaGh = ($LASTEXITCODE -eq 0)
    }
    if ($viaGh) {
        $tag = if ($release -eq "latest") { @() } else { @($release) }
        Quietly { gh release download @tag --repo $repo --dir $dir --pattern dispatch.jar --pattern dispatch.cmd --pattern SHA256SUMS }
        if ($LASTEXITCODE -ne 0) { return $false }
    } else {
        $base = if ($release -eq "latest") { "https://github.com/$repo/releases/latest/download" } else { "https://github.com/$repo/releases/download/$release" }
        try {
            foreach ($asset in @("dispatch.jar", "dispatch.cmd", "SHA256SUMS")) {
                Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $dir $asset)
            }
        } catch {
            Write-Host $_.Exception.Message -ForegroundColor Red
            return $false
        }
    }
    # Verify exactly the two files we install; SHA256SUMS also lists the Unix launcher, which we didn't download.
    $sums = Get-Content (Join-Path $dir "SHA256SUMS")
    foreach ($file in @("dispatch.jar", "dispatch.cmd")) {
        $line = $sums | Where-Object { $_ -match "  $([regex]::Escape($file))$" } | Select-Object -First 1
        if (-not $line) { throw "SHA256SUMS does not list $file" }
        $expected = ($line -split "\s+")[0]
        $actual = (Get-FileHash (Join-Path $dir $file) -Algorithm SHA256).Hash
        if ($actual -ne $expected.ToUpperInvariant()) { throw "the downloaded $file does not match its checksum" }
    }
    return $true
}

# Build from this checkout when run from one, otherwise download the release, and build from a fresh clone only when
# there is none.
$source = $null
$temporary = $null
$jarPath = $null
$launcherPath = $null
$pom = if ($PSScriptRoot) { Join-Path $PSScriptRoot "pom.xml" } else { $null }
$fromCheckout = $pom -and (Test-Path (Join-Path $PSScriptRoot "bin\dispatch.cmd")) -and (Select-String -Quiet -Path $pom -Pattern "<artifactId>dispatch</artifactId>")
if ($fromCheckout) {
    $source = $PSScriptRoot
} else {
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("dispatch-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $temporary | Out-Null
    # DISPATCH_REF must resolve to a release (main -> latest, v* -> that tag) to be downloadable; any other ref names
    # a branch or commit, which only a source build can produce.
    $attemptDownload = (-not $env:DISPATCH_FROM_SOURCE) -and (($ref -eq "main") -or ($ref -like "v*"))
    if ($attemptDownload) {
        Step "Downloading Dispatch ($repo)"
        if (Get-Release $temporary) {
            $jarPath = Join-Path $temporary "dispatch.jar"
            $launcherPath = Join-Path $temporary "dispatch.cmd"
        } else {
            Step "Could not download a release (see above); building from source instead (no web UI)"
        }
    }
    if (-not $jarPath) {
        $source = Join-Path $temporary "source"
        if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "git is needed to build from source: install Git for Windows from https://git-scm.com" }
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
}

try {
    if (-not $jarPath) {
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
        $jarPath = $jar.FullName
        $launcherPath = Join-Path $source "bin\dispatch.cmd"
    }

    Step "Installing into $target"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item $jarPath (Join-Path $target "dispatch.jar") -Force
    Copy-Item $launcherPath (Join-Path $target "dispatch.cmd") -Force

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @(if ($userPath) { $userPath -split ";" | Where-Object { $_ } })
    if ($entries -notcontains $target) {
        [Environment]::SetEnvironmentVariable("Path", (($entries + $target) -join ";"), "User")
        $env:Path = "$env:Path;$target"
        Step "Added $target to your PATH; open a new terminal to use dispatch there"
    }
    Step "Installed. Set it up with: dispatch init, or in your browser: dispatch ui"
} finally {
    if ($temporary) { Remove-Item -Recurse -Force $temporary -ErrorAction SilentlyContinue }
}
