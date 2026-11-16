import subprocess
import time
import sys

def run_adb(args):
    subprocess.run(['adb', '-s', 'emulator-5554'] + args, check=True)

def clear_and_type(x, y, text):
    run_adb(['shell', 'input', 'tap', str(x), str(y)])
    time.sleep(0.3)
    run_adb(['shell', 'input', 'keyevent', 'KEYCODE_MOVE_END'])
    for _ in range(45):
        run_adb(['shell', 'input', 'keyevent', '67'])
    escaped = text.replace('!', r'\!')
    run_adb(['shell', 'input', 'text', escaped])
    time.sleep(0.3)

if __name__ == '__main__':
    email = sys.argv[1]
    password = sys.argv[2]
    clear_and_type(300, 560, email)
    clear_and_type(300, 790, password)
    # Tap login button
    time.sleep(0.5)
    run_adb(['shell', 'input', 'tap', '540', '970'])
