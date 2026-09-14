param(
    [Parameter(Mandatory = $true)]
    [string] $Password
)

$ErrorActionPreference = 'Stop'
$salt = New-Object byte[] 16
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($salt)
$random.Dispose()
$kdf = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
    $Password,
    $salt,
    120000,
    [System.Security.Cryptography.HashAlgorithmName]::SHA256)
$derived = $kdf.GetBytes(32)
$encode = {
    param([byte[]] $Bytes)
    ([Convert]::ToBase64String($Bytes)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
$saltEncoded = & $encode $salt
$derivedEncoded = & $encode $derived
"pbkdf2-sha256`$120000`$$saltEncoded`$$derivedEncoded"
