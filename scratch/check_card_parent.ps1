[xml]$x = Get-Content scratch/s10_error.xml
$btn = $x.SelectSingleNode("//node[contains(@text, 'Th')]")
$p = $btn.ParentNode
Write-Host "Btn Parent: class='$($p.class)', bounds='$($p.bounds)', clickable='$($p.clickable)', resId='$($p.'resource-id')'"
$p2 = $p.ParentNode
Write-Host "Card Parent: class='$($p2.class)', bounds='$($p2.bounds)', resId='$($p2.'resource-id')'"
