$email = $args[0]
$password = $args[1]

# 1. Reset fields by visiting register then back
adb -s emulator-5554 shell input keyevent 111
Start-Sleep -Milliseconds 300
adb -s emulator-5554 shell input tap 540 1650
Start-Sleep -Milliseconds 800
adb -s emulator-5554 shell input tap 540 1750
Start-Sleep -Milliseconds 800

# 2. Tap email input and type
adb -s emulator-5554 shell input tap 541 1087
Start-Sleep -Milliseconds 300
adb -s emulator-5554 shell "input text '$email'"
Start-Sleep -Milliseconds 300

# 3. Tap password input and type
adb -s emulator-5554 shell input tap 541 1311
Start-Sleep -Milliseconds 300
adb -s emulator-5554 shell "input text '$password'"
Start-Sleep -Milliseconds 300

# 4. Hide keyboard
adb -s emulator-5554 shell input keyevent 111
Start-Sleep -Milliseconds 300

# 5. Tap submit
adb -s emulator-5554 shell input tap 540 1493
Start-Sleep -Seconds 2
