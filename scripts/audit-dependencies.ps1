# Input is the JSON tree produced by maven-dependency-plugin:tree, including test scope.
param([string]$Tree = 'target/dependencies.json')
$ErrorActionPreference = 'Stop'
$coordinates = @{}
function Add-Dependency($node) {
    $name = "$($node.groupId):$($node.artifactId)"
    $coordinates["${name}@$($node.version)"] = @{ package = @{ ecosystem = 'Maven'; name = $name }; version = $node.version }
    foreach ($child in $node.children) { Add-Dependency $child }
}
$graph = Get-Content -Raw -LiteralPath $Tree | ConvertFrom-Json
foreach ($child in $graph.children) { Add-Dependency $child }
$queries = @($coordinates.Values)
if ($queries.Count -eq 0) { throw 'The dependency inventory is empty.' }
$payload = @{queries = $queries} | ConvertTo-Json -Depth 5 -Compress
$response = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/querybatch' -ContentType 'application/json' -Body $payload -TimeoutSec 60
if ($response.results.Count -ne $queries.Count) { throw 'Incomplete advisory response.' }
$findings = 0
for ($i = 0; $i -lt $queries.Count; $i++) {
    foreach ($advisory in $response.results[$i].vulns) {
        Write-Output "$($queries[$i].package.name) $($queries[$i].version) $($advisory.id)"
        $findings++
    }
}
Write-Output "Checked $($queries.Count) Maven coordinates. Advisory matches: $findings."
if ($findings) { exit 1 }
