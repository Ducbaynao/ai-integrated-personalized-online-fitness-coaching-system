$checklistFile = "docs/08-ai/p2-m4-f04-closure-checklist.md"
$content = Get-Content -Path "scratch/new_checklist.md" -Raw -Encoding utf8
[System.IO.File]::WriteAllText((Resolve-Path $checklistFile), $content, [System.Text.Encoding]::UTF8)
Write-Output "DONE"
