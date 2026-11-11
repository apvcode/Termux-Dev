"""Runs the assistant's Python inside the app: output is captured, time is limited, and it can be stopped."""
import ctypes
import io
import json
import os
import sys
import threading
import traceback
import urllib.request
import zipfile

_worker = None


def run(code, cwd, timeout, libs):
    """Runs `code` in a thread in the folder `cwd`; returns everything it printed (and the error, if any)."""
    global _worker
    os.makedirs(cwd, exist_ok=True)
    os.makedirs(libs, exist_ok=True)
    if libs not in sys.path:
        sys.path.insert(0, libs)
    out = io.StringIO()
    done = threading.Event()

    def target():
        old = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = out
        previous = os.getcwd()
        try:
            os.chdir(cwd)
            exec(compile(code, "<python>", "exec"), {"__name__": "__main__"})
        except SystemExit:
            pass
        except BaseException:
            traceback.print_exc()
        finally:
            sys.stdout, sys.stderr = old
            try:
                os.chdir(previous)
            except Exception:
                pass
            done.set()

    _worker = threading.Thread(target=target, daemon=True)
    _worker.start()
    if not done.wait(timeout):
        interrupt()
        done.wait(3)
        out.write("\n[stopped: it took longer than %d seconds]\n" % timeout)
    return out.getvalue()


def interrupt():
    """Raises KeyboardInterrupt inside the running code (it stops at once unless it sits in a long native call)."""
    t = _worker
    if t is not None and t.is_alive():
        ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))


def _get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "devx-pip"})
    return urllib.request.urlopen(request, timeout=40).read()


def pip_install(name, libs, depth=0, seen=None):
    """Installs a pure-Python package (and what it needs) from PyPI into `libs`. Packages with native code are not possible."""
    seen = seen if seen is not None else set()
    key = name.lower().replace("_", "-")
    if key in seen or depth > 3:
        return ""
    seen.add(key)
    os.makedirs(libs, exist_ok=True)
    info = json.loads(_get("https://pypi.org/pypi/%s/json" % name))
    wheel = None
    for f in info.get("urls", []):
        if f.get("packagetype") == "bdist_wheel" and f["filename"].endswith("none-any.whl"):
            wheel = f
            break
    if wheel is None:
        raise RuntimeError("%s has no pure-Python wheel (it has native code or only a source archive)" % name)
    with zipfile.ZipFile(io.BytesIO(_get(wheel["url"]))) as z:
        z.extractall(libs)
    log = "installed %s %s\n" % (name, info["info"]["version"])
    for req in info["info"].get("requires_dist") or []:
        if ";" in req and "extra" in req.split(";", 1)[1]:
            continue
        if ";" in req:
            marker = req.split(";", 1)[1]
            if "sys_platform" in marker or "platform_system" in marker or "python_version <" in marker:
                continue
        dep = req.split(";")[0]
        for sep in "<>=!~ ([":
            dep = dep.split(sep)[0]
        dep = dep.strip()
        if dep:
            try:
                log += pip_install(dep, libs, depth + 1, seen)
            except Exception as e:
                log += "could not install %s: %s\n" % (dep, e)
    if libs not in sys.path:
        sys.path.insert(0, libs)
    return log
