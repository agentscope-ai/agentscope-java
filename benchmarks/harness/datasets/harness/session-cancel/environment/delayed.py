import time
from pathlib import Path
Path('started').write_text('yes')
time.sleep(30)
Path('late-write').write_text('unexpected')
