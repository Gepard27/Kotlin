$ErrorActionPreference = 'Stop'
$jdkRoot = Join-Path $PSScriptRoot '../.tools/jdk21'
$localJdk = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
try {
    if ($localJdk) {
        $env:JAVA_HOME = $localJdk.FullName
    }
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '../.tools/gradle-home'
    & "$PSScriptRoot/gradlew.bat" -p $PSScriptRoot @args
    $gradleExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
}
exit $gradleExitCode
