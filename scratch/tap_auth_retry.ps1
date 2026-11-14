[xml]$x = Get-Content scratch/screen_after_rr.xml
$btn = $x.SelectSingleNode('//node[contains(@text, "Th")]')
Write-Host "Btn text: $($btn.text), bounds: $($btn.bounds)"
if ($btn.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $tapX = [int]([int]$matches[1] + [int]$matches[3]) / 2
    $tapY = [int]([int]$matches[2] + [int]$matches[4]) / 2
    Write-Host "Tap: $tapX, $tapY"
    adb -s emulator-5554 shell input tap $tapX $tapY
}
