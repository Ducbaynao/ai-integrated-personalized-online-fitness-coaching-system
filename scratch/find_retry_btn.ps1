[xml]$x = Get-Content scratch/s10_error.xml
$btn = $x.SelectSingleNode("//node[contains(@text, 'Th') or @content-desc='Thử lại']")
$card = $x.SelectSingleNode("//node[contains(@text, 'Mất kết nối') or contains(@text, 'M\?t k\?t n\?i')]")
Write-Host "Card text: $($card.text), bounds: $($card.bounds)"
Write-Host "Btn text: $($btn.text), bounds: $($btn.bounds), desc: $($btn.'content-desc')"
