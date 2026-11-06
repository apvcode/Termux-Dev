# Drives a program in a real pseudo-terminal: waits for texts on the screen, sends keys, reports what was
# printed and whether the program ended. Used by the end-to-end tests (see tests/e2e/helpers.ts).
import pty, os, sys, time, select, re, json, signal
cfg=json.loads(sys.argv[1])
T0=time.time()
pid,fd=pty.fork()
if pid==0:
    os.chdir(cfg['cwd']); env=dict(os.environ); env.update(cfg['env']); env['TERM']='xterm-256color'
    os.execvpe(cfg['cmd'][0],cfg['cmd'],env)
import fcntl,termios,struct
fcntl.ioctl(fd,termios.TIOCSWINSZ,struct.pack('HHHH',40,120,0,0))
buf=''; ansi=re.compile(r'\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[()][A-Z0-9]|\x1b[78=>]')
def pump(t):
    global buf
    end=time.time()+t
    while time.time()<end:
        r,_,_=select.select([fd],[],[],0.05)
        if r:
            try: d=os.read(fd,65536)
            except OSError: return False
            if not d: return False
            buf+=d.decode('utf8','replace')
    return True
mark=0; log=[]
dbg=os.environ.get('PTY_DEBUG')
def D(*a):
    if dbg: sys.stderr.write(' '.join(str(x) for x in a)+'\n'); sys.stderr.flush()
for step in cfg['steps']:
    deadline=time.time()+step.get('timeout',15)
    pat=step.get('wait')
    ok=True
    if pat:
        while True:
            pump(0.1)
            if re.search(pat, ansi.sub('',buf[mark:])): break
            if time.time()>deadline: ok=False; break
    else:
        pump(step.get('ms',300)/1000.0)
    D('step',round(time.time()-T0,2),pat,ok,len(buf))
    log.append({'wait':pat,'ok':ok})
    if 'send' in step:
        mark=len(buf)
        os.write(fd,step['send'].encode())
        pump(step.get('after',0.3))
# let it finish / exit
exited=False; code=None
end=time.time()+cfg.get('exitWait',5)
while time.time()<end:
    pump(0.1)
    try:
        p,st=os.waitpid(pid,os.WNOHANG)
        if p: exited=True; code=os.waitstatus_to_exitcode(st); break
    except ChildProcessError: exited=True; break
if not exited:
    try: os.kill(pid,signal.SIGKILL)
    except Exception: pass
print(json.dumps({'output':buf,'plain':ansi.sub('',buf),'exited':exited,'code':code,'steps':log,'seconds':round(time.time()-T0,2)}))
