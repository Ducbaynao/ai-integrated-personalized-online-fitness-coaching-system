$passwords = @("12345678", "123456789", "phamduc", "phamduc01", "Duc01@123", "Duc123456", "Admin123!", "admin", "admin123", "password", "Password@123", "12345678aA@", "Test1234!", "Fitness123!", "Fitness@123", "StrongPassword123!")
$emails = @("phamducyb01@gmail.com", "smoke.trainer@example.com")

foreach ($email in $emails) {
    foreach ($p in $passwords) {
        $body = @{ email = $email; password = $p } | ConvertTo-Json
        try {
            $res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/sessions" -Method Post -Body $body -ContentType "application/json"
            Write-Host "FOUND: $email with password '$p'"
            break
        } catch {}
    }
}
