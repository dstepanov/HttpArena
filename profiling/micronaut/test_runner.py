"""Exercise the profiling drivers without starting Java or generating traffic."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="micronaut profile ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        jar = self.root / "jars/tuned/target/micronaut-tuned-httparena.jar"
        jar.parent.mkdir(parents=True)
        jar.touch()
        self.tool("taskset", "os.execvp(sys.argv[3], sys.argv[3:])")
        self.tool("java", """
Path(os.environ['PID_FILE']).write_text(str(os.getpid()))
if os.environ.get('FAIL') == 'server': sys.exit(42)
if os.environ.get('FAIL') == 'server-late': time.sleep(0.15); sys.exit(42)
while True: time.sleep(0.1)
""")
        self.tool("curl", """
if os.environ.get('FAIL') == 'occupied': sys.exit(0)
try: os.kill(int(Path(os.environ['PID_FILE']).read_text()), 0)
except (FileNotFoundError, ProcessLookupError): sys.exit(7)
sys.exit(7 if os.environ.get('FAIL') == 'readiness' else 0)
""")
        self.tool("sleep", "time.sleep(0.02)")
        self.tool("load", """
calls = Path(os.environ['CALLS_FILE'])
previous = calls.read_text().splitlines() if calls.exists() else []
with calls.open('a') as f: f.write(json.dumps(sys.argv[1:]) + '\\n')
phase = 'warmup' if not previous else 'load'
if os.environ.get('FAIL') == phase: sys.exit(42)
if previous: time.sleep(0.2)
""")
        self.tool("asprof", """
os.kill(int(sys.argv[-1]), 0)
if os.environ.get('FAIL') == 'profiler': sys.exit(43)
if os.environ.get('FAIL') != 'empty':
    Path(sys.argv[sys.argv.index('-f') + 1]).write_text('java/lang/Thread.run;work 1\\n')
""")
        self.env = dict(os.environ, JAVA_HOME="", PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        ASPROF=str(self.bin / "asprof"), JARS=str(self.root / "jars"),
                        OUT=str(self.root / "out"), PID_FILE=str(self.root / "server.pid"),
                        CALLS_FILE=str(self.root / "calls"))

    def tool(self, name, body):
        script = self.bin / name
        script.write_text(f"#!{sys.executable}\nimport json, os, sys, time\nfrom pathlib import Path\n" + body + "\n")
        script.chmod(0o755)

    def run_profile(self, failure="", *args):
        env = dict(self.env, FAIL=failure)
        return subprocess.run(["bash", str(HERE / "run.sh"), "tuned", "test", str(self.bin / "load"), *args],
                              env=env, capture_output=True, text=True, timeout=10)

    def assert_server_stopped(self):
        pid_file = self.root / "server.pid"
        if pid_file.exists():
            with self.assertRaises(ProcessLookupError):
                os.kill(int(pid_file.read_text()), 0)

    def test_success_preserves_arguments_and_cleans_up(self):
        args = ("http://localhost/?a=1&b=2", "a b; $(false)")
        result = self.run_profile("", *args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("tuned test done", result.stdout.strip())
        self.assertEqual([list(args), list(args)], [json.loads(x) for x in (self.root / "calls").read_text().splitlines()])
        self.assert_server_stopped()

    def test_failures_stop_the_run_and_clean_up(self):
        for failure in ("occupied", "server", "server-late", "readiness", "warmup", "load", "profiler", "empty"):
            with self.subTest(failure=failure):
                (self.root / "calls").unlink(missing_ok=True)
                result = self.run_profile(failure)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertNotIn("done", result.stdout)
                self.assert_server_stopped()

    def test_driver_propagates_child_failure(self):
        env = dict(self.env, WRK=str(self.bin / "load"), GCANNON=str(self.bin / "load"))
        env.pop("ASPROF")
        result = subprocess.run(["bash", str(HERE / "all.sh"), "production"], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("ALLDONE", result.stdout)

    def test_termination_cleans_up(self):
        self.tool("asprof", "Path(os.environ['OUT'], 'profiler.pid').write_text(str(os.getpid()))\ntime.sleep(30)")
        with subprocess.Popen(["bash", str(HERE / "run.sh"), "tuned", "test", str(self.bin / "load")],
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
            import time
            deadline = time.monotonic() + 5
            while not (self.root / "out/profiler.pid").exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue((self.root / "out/profiler.pid").exists())
            process.terminate()
            process.communicate(timeout=5)
            self.assertNotEqual(0, process.returncode)
        self.assert_server_stopped()
        with self.assertRaises(ProcessLookupError):
            os.kill(int((self.root / "out/profiler.pid").read_text()), 0)


if __name__ == "__main__":
    unittest.main()
