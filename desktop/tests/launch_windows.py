"""Verify the actual packaged GUI opens a visible native window and exits cleanly."""
import ctypes
from ctypes import wintypes
from pathlib import Path
import subprocess,sys,time
exe=str(Path(sys.argv[1]).resolve())
user=ctypes.WinDLL('user32',use_last_error=True)
callback_type=ctypes.WINFUNCTYPE(wintypes.BOOL,wintypes.HWND,wintypes.LPARAM)
user.EnumWindows.argtypes=[callback_type,wintypes.LPARAM]
user.GetWindowThreadProcessId.argtypes=[wintypes.HWND,ctypes.POINTER(wintypes.DWORD)]
user.IsWindowVisible.argtypes=[wintypes.HWND]
user.GetWindowTextW.argtypes=[wintypes.HWND,wintypes.LPWSTR,ctypes.c_int]
user.PostMessageW.argtypes=[wintypes.HWND,wintypes.UINT,wintypes.WPARAM,wintypes.LPARAM]
process=subprocess.Popen([exe])
try:
 deadline=time.monotonic()+40
 found=[]
 @callback_type
 def visit(handle,_):
  pid=wintypes.DWORD();user.GetWindowThreadProcessId(handle,ctypes.byref(pid))
  text=ctypes.create_unicode_buffer(256);user.GetWindowTextW(handle,text,256)
  if pid.value==process.pid and user.IsWindowVisible(handle) and text.value=='NekoKEM':found.append(handle)
  return True
 while time.monotonic()<deadline and not found:
  assert process.poll() is None,'GUI exited before opening a window'
  user.EnumWindows(visit,0);time.sleep(.2)
 assert found,'Visible NekoKEM window not found'
 time.sleep(5)
 assert process.poll() is None,'GUI failed after initial launch'
 assert user.PostMessageW(found[0],0x10,0,0),'Cannot request normal window close'
 assert process.wait(timeout=20)==0,'GUI did not exit cleanly'
 print('Actual Windows GUI: visible window, stable launch and normal close passed')
finally:
 if process.poll() is None:process.terminate();process.wait(timeout=10)
