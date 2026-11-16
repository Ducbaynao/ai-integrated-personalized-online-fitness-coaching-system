import subprocess
import time

def adb(args):
    return subprocess.run(['adb', '-s', 'emulator-5554'] + args, check=True)

# 1. Reset by toggling register/login
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.3)
adb(['shell', 'input', 'tap', '540', '1650'])
time.sleep(0.8)
adb(['shell', 'input', 'tap', '540', '1750'])
time.sleep(0.8)

# 2. Tap email (541, 566)
adb(['shell', 'input', 'tap', '541', '566'])
time.sleep(0.3)
adb(['shell', 'input', 'text', 'student.f02.runtime@example.com'])
time.sleep(0.3)

# 3. Press TAB to move focus to password!
adb(['shell', 'input', 'keyevent', '61'])
time.sleep(0.3)
# Type password
adb(['shell', 'input', 'text', 'StrongPassword123'])
time.sleep(0.2)
adb(['shell', 'input', 'text', r'\!'])
time.sleep(0.3)

# 4. Press ENTER (66) to submit or dismiss and tap submit
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.3)
adb(['shell', 'input', 'tap', '540', '973'])
time.sleep(2)
