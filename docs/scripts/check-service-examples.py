"""Check scenario fixtures locally; never call GitHub, models, or Service."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import re
from datetime import date

EXAMPLES = Path(__file__).resolve().parents[1] / "examples" / "service"


def check_order_query():
    if not shutil.which("javac") or not shutil.which("java"):
        raise RuntimeError("JDK 17+ is required for the Java scenario fixture")
    with tempfile.TemporaryDirectory(prefix="agentscope-docs-order-") as directory:
        work = Path(directory)
        for name in ("OrderQuery.java", "OrderQueryTest.java"):
            shutil.copyfile(EXAMPLES / "incident-to-pr" / (name + ".txt"), work / name)

        def run_checks():
            compile_result = subprocess.run(
                ["javac", "--release", "17", "-d", "out", "OrderQuery.java", "OrderQueryTest.java"],
                cwd=work, capture_output=True, text=True,
            )
            if compile_result.returncode:
                raise RuntimeError(compile_result.stderr)
            return subprocess.run(
                ["java", "-cp", "out", "OrderQueryTest"], cwd=work,
                capture_output=True, text=True,
            )

        before = run_checks()
        if before.returncode != 1 or "5 checks, 3 failures" not in before.stdout:
            raise RuntimeError("Unexpected Java baseline:\n" + before.stdout + before.stderr)
        source = work / "OrderQuery.java"
        text = source.read_text(encoding="utf-8")
        text = text.replace(
            "return List.copyOf(orders);",
            "return orders.stream()\n"
            "                .filter(order -> status == null || status.isBlank() || order.status().equals(status))\n"
            "                .skip(((long) page - 1) * size).limit(size).toList();",
        )
        source.write_text(text, encoding="utf-8")
        after = run_checks()
        if after.returncode or "5 checks, 0 failures" not in after.stdout:
            raise RuntimeError("Unexpected reference repair:\n" + after.stdout + after.stderr)
    print("Java fixture: 3 of 5 checks fail initially; all 5 pass with the reference repair.")


def check_service_requests():
    slugs = ("in-product-delivery", "incident-to-pr", "document-verification",
             "business-assistant", "scheduled-research", "agent-as-tool")
    requests = {}
    for slug in slugs:
        body = json.loads((EXAMPLES / slug / "input.json.txt").read_text())
        requests[slug] = body
        if slug == "business-assistant":
            assert set(body) == {"message"} and body["message"]
        else:
            assert body["title"] and body["input"]["request"]
        for language in ("zh", "en"):
            page = EXAMPLES.parents[1] / "v2" / language / "service" / "cases" / (slug + ".md")
            for block in re.findall(r"```bash\n(.*?)```", page.read_text(), re.S):
                parsed = subprocess.run(["bash", "-n"], input=block, text=True, capture_output=True)
                if parsed.returncode:
                    raise RuntimeError(str(page) + ": " + parsed.stderr)
    sources = requests["in-product-delivery"]["input"]["sources"]
    assert {s["version"] for s in sources} == {"customer-v1", "product-v1", "delivery-v1"}
    invoice = requests["document-verification"]["input"]
    extracted = invoice["extracted"]
    expected = extracted["quantity"] * extracted["unit_price"]
    assert expected == 128 and extracted["total"] == 182
    assert str(expected) + ".00" in invoice["pages"][0]["text"]
    assert invoice["pages"][0]["page"] == 1
    orders = json.loads((EXAMPLES / "business-assistant/orders.json.txt").read_text())
    assert orders["synthetic"] is True
    by_id = {o["id"]: o for o in orders["orders"]}
    assert by_id["O-1001"]["owner"] != by_id["O-2001"]["owner"]
    assert by_id["O-1001"]["status"] == "awaiting_stock"
    assert by_id["O-1001"]["guaranteed_arrival"] is False
    research = requests["scheduled-research"]["input"]
    date.fromisoformat(research["research_date"])
    assert research["strategy_version"] and len({s["id"] for s in research["sources"]}) == 2
    review = requests["agent-as-tool"]["input"]
    assert review["supplier_id"] == "V-101"
    assert "No independent security review" in review["sources"][1]["text"]
    print("Six request fixtures: shapes, source versions, expected contradiction and ownership boundaries checked.")
    print("Bash snippets in all twelve scenario pages parse without execution.")


if __name__ == "__main__":
    check_order_query()
    check_service_requests()
    print("Local fixture validation only; no models, GitHub, business systems, or Service were called.")
