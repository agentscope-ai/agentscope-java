import importlib.util
import json
from pathlib import Path

checks = {}
try:
    spec = importlib.util.spec_from_file_location("candidate_stats", "/workspace/stats.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    checks = {
        "empty_list": module.mean([]) is None,
        "empty_generator": module.mean(iter(())) is None,
        "normal": module.mean([2, 4, 6]) == 4,
        "generator": module.mean(iter([1, 2, 3, 4])) == 2.5,
        "signed": module.mean([-4, 2]) == -1,
        "zero": module.mean([0]) == 0,
    }
except Exception as error:
    checks["exception"] = type(error).__name__
Path("/logs/verifier/checks.json").write_text(json.dumps(checks, indent=2))
Path("/logs/verifier/reward.txt").write_text("1" if len(checks) == 6 and all(v is True for v in checks.values()) else "0")
