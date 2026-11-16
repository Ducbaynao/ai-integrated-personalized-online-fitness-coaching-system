import subprocess
import time

def adb(args):
    return subprocess.run(['adb', '-s', 'emulator-5554'] + args, check=True)

# 1. Tap email input
adb(['shell', 'input', 'tap', '540', '1008'])
time.sleep(0.3)
adb(['shell', 'input', 'text', 'smoke.student@example.com'])
time.sleep(0.3)

# 2. Tap password input
adb(['shell', 'input', 'tap', '540', '1232'])
time.sleep(0.3)
adb(['shell', 'input', 'text', 'StrongPassword123'])
time.sleep(0.2)
adb(['shell', 'input', 'text', r'\!'])
time.sleep(0.3)

# 3. Dismiss keyboard
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.4)

# 4. Tap submit
adb(['shell', 'input', 'tap', '540', '1415'])
time.sleep(2.5)
print("Submitted login!")
