import subprocess
import time
import sys

def adb(cmd):
    subprocess.run(['adb', '-s', 'emulator-5554'] + cmd, check=True)

def login(email, password):
    # Dismiss any keyboard
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    # Tap email input
    adb(['shell', 'input', 'tap', '541', '645'])
    time.sleep(0.3)
    # Clear 100 chars
    del_keys = ['67'] * 100
    adb(['shell', 'input', 'keyevent'] + del_keys)
    time.sleep(0.3)
    # Type email
    adb(['shell', 'input', 'text', email])
    time.sleep(0.5)

    # Dismiss keyboard so layout doesn't shift
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    # Tap password input
    adb(['shell', 'input', 'tap', '541', '869'])
    time.sleep(0.3)
    adb(['shell', 'input', 'keyevent'] + del_keys)
    time.sleep(0.3)
    # Type password
    adb(['shell', 'input', 'text', password.replace('!', r'\!')])
    time.sleep(0.5)

    # Dismiss keyboard
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    # Tap submit
    adb(['shell', 'input', 'tap', '540', '1051'])
    time.sleep(2)

if __name__ == '__main__':
    login(sys.argv[1], sys.argv[2])
