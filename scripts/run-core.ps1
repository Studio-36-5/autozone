# Runs the pure-Kotlin domain unit tests (no Android needed).
# Requires: JAVA_HOME (JDK 17) and kotlinc on PATH, or KOTLINC pointing to kotlinc(.bat).
$ErrorActionPreference = "Stop"
$kotlinc = if ($env:KOTLINC) { $env:KOTLINC } else { "kotlinc" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }

$root = Split-Path -Parent $PSScriptRoot
$outJar = Join-Path $root "build\core-test.jar"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outJar) | Out-Null

& $kotlinc "$root\core\src\autozone\core\AutoZoneDomain.kt" "$root\core\test\autozone\core\AutoZoneDomainTest.kt" -include-runtime -d $outJar
& $java -jar $outJar
