"""Observe the real Linux window, WebKit page and settings IPC, with sandboxing retained."""
from pathlib import Path
import argparse
import os
import subprocess
import time


def command(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--log', type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() != 0, 'Run the native GUI as the normal desktop user'
    assert os.environ.get('DISPLAY'), 'Run inside the CI Xvfb/desktop session'
    assert os.environ.get('WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS') not in {'1','true'}, 'Retain the WebKit sandbox'
    args.log.parent.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment['NEKOKEM_NATIVE_STARTUP_EVIDENCE'] = '1'
    environment['GDK_BACKEND'] = 'x11'
    window_manager = subprocess.Popen(['openbox'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # Start the application only after the isolated X11 window manager
        # publishes its desktop properties, avoiding an initial map race.
        deadline = time.monotonic()+10
        while time.monotonic() < deadline:
            assert window_manager.poll() is None, 'Test window manager exited'
            desktops = subprocess.run(['xdotool','get_num_desktops'],
                                      capture_output=True, text=True, timeout=5)
            if desktops.returncode == 0 and desktops.stdout.strip().isdigit() and int(desktops.stdout) > 0:
                break
            time.sleep(0.1)
        else:
            raise AssertionError('Test window manager did not become ready')
        with args.log.open('wb') as log:
            process = subprocess.Popen([str(args.binary.resolve())], env=environment,
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic()+45
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Native GUI exited; inspect startup.log'
                    evidence = args.log.read_text(errors='replace')
                    if all(f'NekoKEM native startup: {stage}' in evidence for stage in ['page-loaded','settings-ready']):
                        break
                    time.sleep(0.2)
                else:
                    raise AssertionError('Real WebKit page-load and settings IPC did not become ready')
                found = None
                # WebKit can finish IPC before the window manager maps the
                # GTK window and publishes its PID. Wait for the same visible
                # window requirements, with a bounded deadline.
                deadline = time.monotonic()+15
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Native GUI exited before its window became visible'
                    search = subprocess.run(['xdotool','search','--onlyvisible','--pid',str(process.pid)],
                                            capture_output=True, text=True, timeout=5)
                    assert search.returncode in {0, 1}, search.stderr
                    for window in search.stdout.splitlines():
                        name = command('xdotool','getwindowname',window)
                        geometry = dict(line.split('=',1) for line in command('xdotool','getwindowgeometry','--shell',window).splitlines())
                        if name == 'NekoKEM' and int(geometry['WIDTH']) >= 760 and int(geometry['HEIGHT']) >= 620:
                            found = window
                            break
                    if found:
                        break
                    time.sleep(0.1)
                assert found, 'NekoKEM native window has the wrong title or dimensions'
                print('Actual Linux NekoKEM window, local WebKit page and real settings IPC passed')
                # A normal WM close reaches Tauri's cleanup path; no SIGKILL.
                subprocess.run(['xdotool','windowactivate','--sync',found], check=True, timeout=10)
                subprocess.run(['xdotool','key','--clearmodifiers','alt+F4'], check=True, timeout=10)
                assert process.wait(timeout=20) == 0, 'Idle native GUI did not close normally'
                print('Actual idle Linux GUI normal window close passed')
            finally:
                if process.poll() is None:
                    process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    finally:
        window_manager.terminate()
        window_manager.wait(timeout=10)
