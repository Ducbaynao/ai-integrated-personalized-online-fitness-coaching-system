import subprocess
import time
import xml.etree.ElementTree as ET
import sys

def adb(cmd):
    subprocess.run(['adb', '-s', 'emulator-5554'] + cmd, check=True)

def dump_and_get_bounds():
    adb(['shell', 'uiautomator', 'dump', '/sdcard/window_dump.xml'])
    adb(['pull', '/sdcard/window_dump.xml', 'scratch/window_dump.xml'])
    tree = ET.parse('scratch/window_dump.xml')
    bounds = {}
    for elem in tree.iter():
        rid = elem.attrib.get('resource-id')
        if rid:
            b_str = elem.attrib.get('bounds')
            # [x1,y1][x2,y2]
            parts = b_str.replace('][', ',').replace('[', '').replace(']', '').split(',')
            x1, y1, x2, y2 = map(int, parts)
            bounds[rid] = ((x1 + x2) // 2, (y1 + y2) // 2)
    return bounds

def login(email, password):
    # Hide keyboard
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    bounds = dump_and_get_bounds()
    print("Detected bounds:", bounds)

    em_x, em_y = bounds['email-input']
    adb(['shell', 'input', 'tap', str(em_x), str(em_y)])
    time.sleep(0.3)
    # Clear existing
    adb(['shell', 'input', 'keyevent'] + ['67'] * 60)
    time.sleep(0.3)
    adb(['shell', 'input', 'text', email])
    time.sleep(0.5)

    # Hide keyboard so layout is restored
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    bounds = dump_and_get_bounds()
    pw_x, pw_y = bounds['password-input']
    adb(['shell', 'input', 'tap', str(pw_x), str(pw_y)])
    time.sleep(0.3)
    adb(['shell', 'input', 'keyevent'] + ['67'] * 60)
    time.sleep(0.3)
    adb(['shell', f"input text '{password}'"])
    time.sleep(0.5)

    # Hide keyboard
    adb(['shell', 'input', 'keyevent', '111'])
    time.sleep(0.5)

    bounds = dump_and_get_bounds()
    sub_x, sub_y = bounds['submit-sign-in']
    adb(['shell', 'input', 'tap', str(sub_x), str(sub_y)])
    time.sleep(2)

if __name__ == '__main__':
    login(sys.argv[1], sys.argv[2])
