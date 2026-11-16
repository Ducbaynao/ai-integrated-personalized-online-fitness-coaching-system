import subprocess
import time
import xml.etree.ElementTree as ET

def adb(args):
    return subprocess.run(['adb', '-s', 'emulator-5554'] + args, capture_output=True, text=True, check=True)

print("1. Tap email")
adb(['shell', 'input', 'tap', '541', '566'])
time.sleep(0.5)

print("2. Type email")
adb(['shell', 'input', 'text', 'student.f02.runtime@example.com'])
time.sleep(0.5)

print("3. Dismiss keyboard")
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.5)

print("4. Tap password")
adb(['shell', 'input', 'tap', '541', '790'])
time.sleep(0.5)

print("5. Type password text")
adb(['shell', 'input', 'text', 'StrongPassword123'])
time.sleep(0.3)
# Send '!' via shift + 1 (KEYCODE_1 is 8)
# In Android input keyevent, sending keycode 8 with meta state 1 (SHIFT_LEFT_ON):
# input keyevent --meta 1 8 or typing '\!'
res = adb(['shell', 'input', 'text', r'\!'])
time.sleep(0.5)

print("6. Dismiss keyboard")
adb(['shell', 'input', 'keyevent', '111'])
time.sleep(0.5)

print("7. Tap submit-sign-in")
adb(['shell', 'input', 'tap', '540', '973'])
time.sleep(2)
print("Done")
