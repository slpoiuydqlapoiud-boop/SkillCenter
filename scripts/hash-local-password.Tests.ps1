$repoRoot = Split-Path -Parent $PSScriptRoot

Describe "local password hash helper" {
    It "prints a Java-compatible PBKDF2 hash without the plaintext password" {
        $output = & (Join-Path $repoRoot "scripts\hash-local-password.ps1") -Password "sample-password"
        $output | Should Match '^pbkdf2-sha256\$120000\$[A-Za-z0-9_-]+\$[A-Za-z0-9_-]+$'
        $output | Should Not Match "sample-password"
    }
}
