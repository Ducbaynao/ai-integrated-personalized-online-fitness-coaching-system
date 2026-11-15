[xml]$x = Get-Content scratch/screen_bundled.xml
$n = $x.SelectSingleNode("//node[contains(@bounds, '1200')]")
if ($n) {
    Write-Host "Found: text='$($n.text)', clickable='$($n.clickable)', enabled='$($n.enabled)', bounds='$($n.bounds)'"
    $parent = $n.ParentNode
    Write-Host "Parent: class='$($parent.class)', clickable='$($parent.clickable)', bounds='$($parent.bounds)', desc='$($parent.'content-desc')'"
    $grand = $parent.ParentNode
    Write-Host "Grandparent: class='$($grand.class)', clickable='$($grand.clickable)', bounds='$($grand.bounds)'"
}
