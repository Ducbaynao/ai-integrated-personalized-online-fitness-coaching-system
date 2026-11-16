$passwords = @("StrongPassword123!", "Password123!", "123456", "SuperSecretPassword123!", "securePassword123")
$emails = @("smoke.trainer@example.com", "smoke.student@example.com", "student.f02.runtime@example.com", "phamducyb01@gmail.com")

foreach ($email in $emails) {
    foreach ($p in $passwords) {
        $body = @{ email = $email; password = $p } | ConvertTo-Json
        try {
            $res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/sessions" -Method Post -Body $body -ContentType "application/json"
            Write-Host "SUCCESS: $email with password '$p' -> AccessToken issued!"
            break
        } catch {
            # continue
        }
    }
}
