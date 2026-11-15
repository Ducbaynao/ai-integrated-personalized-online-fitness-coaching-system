[xml]$x = Get-Content scratch/s10_error.xml
$nodes = $x.SelectNodes("//node[@text != '']")
$res = foreach ($n in $nodes) {
    [PSCustomObject]@{
        Text = $n.text
        ContentDesc = $n.'content-desc'
        ResourceId = $n.'resource-id'
        Bounds = $n.bounds
    }
}
$res | Format-Table -AutoSize
