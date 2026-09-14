function Get-PhaseSurefireEvidence {
    param(
        [Parameter(Mandatory)][string]$SurefireRoot,
        [Parameter(Mandatory)][datetime]$PhaseStartUtc,
        [Parameter(Mandatory)][string]$Phase
    )

    $freshReports = @(Get-ChildItem -LiteralPath $SurefireRoot -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -ge $PhaseStartUtc })
    $postgresReport = @($freshReports | Where-Object { $_.Name -like '*PostgresQualityEvidenceIntegrationTest.xml' })
    if ($postgresReport.Count -ne 1) {
        throw "$Phase PostgresQualityEvidenceIntegrationTest Surefire report is missing or stale"
    }

    $totals = [ordered]@{ Tests = 0L; Failures = 0L; Errors = 0L; Skipped = 0L }
    $postgresSuite = $null
    foreach ($report in $freshReports) {
        [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
        $suite = $xml.testsuite
        if ($null -eq $suite) { continue }
        $totals.Tests += [long]$suite.tests
        $totals.Failures += [long]$suite.failures
        $totals.Errors += [long]$suite.errors
        $totals.Skipped += [long]$suite.skipped
        if ($report.FullName -eq $postgresReport[0].FullName) { $postgresSuite = $suite }
    }
    if ($null -eq $postgresSuite) { throw "$Phase PostgreSQL Surefire XML is invalid" }
    if ($totals.Failures -ne 0 -or $totals.Errors -ne 0) { throw "$Phase Surefire contains failures or errors" }

    $marker = 'CAPABILITY_SKIP: Docker is unavailable'
    $skippedCases = @($postgresSuite.testcase | Where-Object { $null -ne $_.skipped })
    $markedCases = @($skippedCases | Where-Object {
        $text = [string]$_.skipped.InnerText
        ([regex]::Matches($text, [regex]::Escape($marker))).Count -eq 1
    })
    if ($skippedCases.Count -ne [int]$postgresSuite.skipped -or $markedCases.Count -ne $skippedCases.Count) {
        throw "$Phase PostgreSQL skips are not the explicit Docker capability skip"
    }

    [pscustomobject]@{
        Totals = $totals
        PostgresSkipped = [long]$postgresSuite.skipped
        CapabilitySkip = if ([long]$postgresSuite.skipped -gt 0) { "${Phase}: PostgresQualityEvidenceIntegrationTest skipped $($postgresSuite.skipped) test(s): Docker unavailable" } else { $null }
    }
}

Export-ModuleMember -Function Get-PhaseSurefireEvidence
