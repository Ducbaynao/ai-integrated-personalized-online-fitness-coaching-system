[xml]$x = Get-Content scratch/screen_current.xml
$button = $x.SelectSingleNode("//node[contains(@text, 'Cập nhật lại') or contains(@text, 'C\?p nh\?t') or @resource-id='generate-summary-button' or @content-desc='generate-summary-button']")
if ($button) {
    Write-Host "Button found: $($button.text), bounds: $($button.bounds), content-desc: $($button.'content-desc')"
} else {
    $all = $x.SelectNodes("//node[@clickable='true']")
    foreach ($a in $all) {
        Write-Host "Clickable: $($a.text) | $($a.'content-desc') | $($a.bounds)"
    }
}
