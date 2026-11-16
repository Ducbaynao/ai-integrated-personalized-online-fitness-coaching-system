import subprocess
import time

def adb(args):
    return subprocess.run(['adb', '-s', 'emulator-5554'] + args, check=True)

def clear_field(tap_x, tap_y):
    adb(['shell', 'input', 'tap', str(tap_x), str(tap_y)])
    time.sleep(0.2)
    # Move end and delete 60 chars
    adb(['shell', 'input', 'keyevent', '123'])
    for _ in range(6):
        adb(['shell', 'input', 'keyevent'] + ['67'] * 10)
    # Move home and forward delete 60 chars
    adb(['shell', 'input', 'keyevent', '122'])
    for _ in range(6):
        adb(['shell', 'input', 'keyevent'] + ['112'] * 10)
    time.sleep(0.2)

# Clear email
clear_field(541, 566)
# Type email
adb(['shell', 'input', 'text', 'student.f02.runtime@example.com'])
time.sleep(0.3)

# Clear password
clear_field(541, 790)
# Type password
adb(['shell', 'input', 'text', 'StrongPassword123'])
time.sleep(0.2)
adb(['shell', 'input', 'text', r'\!'])
time.sleep(0.3)

# Dismiss keyboard
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.3)

# Tap submit
adb(['shell', 'input', 'tap', '540', '973'])
time.sleep(2)
