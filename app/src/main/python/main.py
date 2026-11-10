import sys

class KotlinStdout:
    def __init__(self, callback):
        self.callback = callback

    def write(self, text):
        if text:
            self.callback.printOutput(text)

    def flush(self):
        pass

class KotlinStdin:
    def __init__(self, callback):
        self.callback = callback

    def readline(self):
        return self.callback.readInput() + "\n"

    def read(self, size=-1):
        return self.callback.readInput()

    def isatty(self):
        return False

def run_code(user_code, io_bridge):
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_stdin = sys.stdin

    sys.stdout = KotlinStdout(io_bridge)
    sys.stderr = KotlinStdout(io_bridge)
    sys.stdin = KotlinStdin(io_bridge)

    try:
        exec(user_code, {})
    except Exception as e:
        sys.stderr.write(f"\nОшибка:\n{e}\n")
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        sys.stdin = old_stdin

    return "Программа завершена"