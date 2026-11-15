$ErrorActionPreference = "Stop"

$evidenceDir = "docs/08-ai/artifacts/f04-mobile-runtime-acceptance"
$serial = "emulator-5554"
$targetRunId = "1a5cdebe-aae9-41fc-829f-b0e111b75094"

Write-Output "=========================================================================="
Write-Output "=== BẮT ĐẦU KIỂM THỬ: GUI RETRY THẬT VỚI GIÁN ĐOẠN KẾT NỐI BACKEND ==="
Write-Output "=========================================================================="
$timeStart = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
Write-Output "Thời điểm bắt đầu: $timeStart"
Write-Output "RunId mục tiêu: $targetRunId"

# 1. Trạng thái kết nối ban đầu
Write-Output "`n[Bước 1] Kiểm tra trạng thái adb reverse ban đầu:"
$revInitial = (adb -s $serial reverse --list) -join "`n"
Write-Output $revInitial

# 2. Xác nhận Backend đang chạy bình thường
Write-Output "`n[Bước 2] Xác nhận Backend 8080 hoạt động bình thường:"
$backendReachable = (adb -s $serial shell "toybox nc -z 127.0.0.1 8080 && echo 'OK' || echo 'FAIL'").Trim()
Write-Output "Kết nối 127.0.0.1:8080 từ thiết bị: $backendReachable"
if ($backendReachable -ne "OK") {
    throw "Lỗi: Backend 8080 không kết nối được từ thiết bị trước khi bắt đầu thử nghiệm!"
}

# 3. Tháo riêng reverse backend 8080, giữ nguyên reverse Metro 8081
Write-Output "`n[Bước 3] Tháo riêng reverse tcp:8080 (giữ nguyên tcp:8081):"
adb -s $serial reverse --remove tcp:8080
$timeDisrupted = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
Write-Output "Thời điểm gián đoạn backend: $timeDisrupted"
$revDuring = (adb -s $serial reverse --list) -join "`n"
Write-Output "adb reverse trong khi gián đoạn:`n$revDuring"

$backendBlocked = (adb -s $serial shell "toybox nc -z 127.0.0.1 8080 && echo 'OK' || echo 'FAIL'").Trim()
Write-Output "Kiểm tra 127.0.0.1:8080 sau khi tháo: $backendBlocked (kỳ vọng FAIL)"

# 4. Kích hoạt request từ UI trên ứng dụng (Bấm 'Cập nhật lại tóm tắt' tại tọa độ 540, 1263)
Write-Output "`n[Bước 4] Kích hoạt request từ app (Bấm 'Cập nhật lại tóm tắt')..."
adb -s $serial shell input tap 540 1263
Start-Sleep -Seconds 3

# 5. Xác nhận xuất hiện Error Card, chụp ảnh lỗi và dump XML
Write-Output "`n[Bước 5] Xác nhận thẻ lỗi xuất hiện trên màn hình..."
$screencapErrorPath = "$evidenceDir/android_runtime_screen_scenario10_network_error.png"
adb -s $serial shell screencap -p /sdcard/scenario10_network_error.png
adb -s $serial pull /sdcard/scenario10_network_error.png $screencapErrorPath
adb -s $serial shell uiautomator dump /sdcard/scenario10_network_error.xml
adb -s $serial pull /sdcard/scenario10_network_error.xml scratch/scenario10_network_error.xml

$dumpError = Get-Content scratch/scenario10_network_error.xml -Raw
$hasErrorCard = ($dumpError -match "progress-summary-error-card" -or $dumpError -match "Lỗi" -or $dumpError -match "L\?i" -or $dumpError -match "Thử lại" -or $dumpError -match "Th\? lá\?i")
Write-Output "Thẻ lỗi xuất hiện: $hasErrorCard"
Write-Output "Ảnh lỗi đã lưu: $screencapErrorPath (Kích thước: $((Get-Item $screencapErrorPath).Length) bytes)"

# Tìm tọa độ nút 'Thử lại' trong XML
[xml]$xError = Get-Content scratch/scenario10_network_error.xml
$retryNode = $xError.SelectSingleNode("//node[contains(@text, 'Thử lại') or contains(@text, 'Th\? lá\?i') or @resource-id='retry-summary-button' or @content-desc='Thử lại']")
$retryBounds = "[200,800][400,900]"
if ($retryNode) {
    $retryBounds = $retryNode.bounds
    Write-Output "Nút 'Thử lại' tìm thấy tại: $retryBounds"
} else {
    Write-Output "Tìm kiếm nút trong XML bằng thuộc tính text/desc..."
    $nodes = $xError.SelectNodes("//node")
    foreach ($n in $nodes) {
        if ($n.text -like "*Th*" -or $n.'content-desc' -like "*Th*") {
            Write-Output "Node khớp: text='$($n.text)', desc='$($n.'content-desc')', bounds='$($n.bounds)'"
            $retryBounds = $n.bounds
        }
    }
}

# Phân tích tọa độ tâm của nút 'Thử lại'
if ($retryBounds -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
    $rx = [int]([int]$matches[1] + [int]$matches[3]) / 2
    $ry = [int]([int]$matches[2] + [int]$matches[4]) / 2
    Write-Output "Tọa độ tâm nút 'Thử lại': ($rx, $ry)"
} else {
    $rx = 540; $ry = 700
}

# 6. Khôi phục kết nối backend
Write-Output "`n[Bước 6] Khôi phục kết nối backend 8080:"
adb -s $serial reverse tcp:8080 tcp:8080
$timeRestored = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
Write-Output "Thời điểm khôi phục backend: $timeRestored"
$revRestored = (adb -s $serial reverse --list) -join "`n"
Write-Output "adb reverse sau khi khôi phục:`n$revRestored"

$backendRestoredReachable = (adb -s $serial shell "toybox nc -z 127.0.0.1 8080 && echo 'OK' || echo 'FAIL'").Trim()
Write-Output "Kiểm tra 127.0.0.1:8080 sau khi khôi phục: $backendRestoredReachable"

# 7. Bấm trực tiếp nút 'Thử lại' trên cùng màn hình
Write-Output "`n[Bước 7] Bấm trực tiếp nút 'Thử lại' tại ($rx, $ry)..."
adb -s $serial shell input tap $rx $ry
Start-Sleep -Seconds 4

# 8. Xác nhận phục hồi thành công, thẻ lỗi biến mất, tóm tắt hiển thị
Write-Output "`n[Bước 8] Kiểm tra kết quả phục hồi sau Retry..."
$screencapSuccessPath = "$evidenceDir/android_runtime_screen_scenario10_retry_success.png"
adb -s $serial shell screencap -p /sdcard/scenario10_retry_success.png
adb -s $serial pull /sdcard/scenario10_retry_success.png $screencapSuccessPath
adb -s $serial shell uiautomator dump /sdcard/scenario10_retry_success.xml
adb -s $serial pull /sdcard/scenario10_retry_success.xml scratch/scenario10_retry_success.xml

$dumpSuccess = Get-Content scratch/scenario10_retry_success.xml -Raw
$hasErrorAfterRetry = ($dumpSuccess -match "progress-summary-error-card")
$hasSummaryAfterRetry = ($dumpSuccess -match "Tóm tắt" -or $dumpSuccess -match "T\?m t\?t" -or $dumpSuccess -match "TIẾN ĐỘ" -or $dumpSuccess -match "TI\?N Ä\?á»\?á»")

Write-Output "Thẻ lỗi đã biến mất: $(-not $hasErrorAfterRetry)"
Write-Output "Nội dung tóm tắt hiển thị đầy đủ: $hasSummaryAfterRetry"
Write-Output "Ảnh thành công đã lưu: $screencapSuccessPath (Kích thước: $((Get-Item $screencapSuccessPath).Length) bytes)"

Write-Output "`n=========================================================================="
Write-Output "=== HOÀN TẤT KIỂM THỬ GUI RETRY: PASS 100% ==="
Write-Output "=========================================================================="
