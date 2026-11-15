$p = Start-Process -FilePath "adb" -ArgumentList "-s", "emulator-5554", "emu", "kill" -PassThru
if (-not $p.WaitForExit(10000)) {
    $p.Kill()
    Write-Host "adb emu kill timed out after 10s"
} else {
    Write-Host "adb emu kill completed"
}
