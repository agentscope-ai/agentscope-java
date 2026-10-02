"""Check scenario fixtures locally; never call GitHub, models, or Service."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile

EXAMPLES = Path(__file__).resolve().parents[1] / "examples" / "service"


def check_order_query():
    if not shutil.which("javac") or not shutil.which("java"):
        raise RuntimeError("JDK 17+ is required for the Java scenario fixture")
    with tempfile.TemporaryDirectory(prefix="agentscope-docs-order-") as directory:
        work = Path(directory)
        for name in ("OrderQuery.java", "OrderQueryTest.java"):
            shutil.copyfile(EXAMPLES / "sdlc-team" / (name + ".txt"), work / name)

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


def check_business_data():
    data = json.loads((EXAMPLES / "order-fulfillment/business-data.json.txt").read_text(encoding="utf-8"))
    assert data["synthetic"] is True
    orders = {o["orderId"]: o for o in data["orders"]}
    assert len(orders) == 3
    assert orders["O-1001"]["status"] == "awaiting_stock"
    assert orders["O-1002"]["status"] == "shipped"
    assert orders["O-1003"]["status"] == "cancelled"
    assert data["request"]["orderId"] in orders
    stock = {i["warehouse"]: i["available"] for i in data["inventory"]}
    assert stock == {"W-A": 0, "W-B": 8}
    shipment = next(v for v in data["logistics"] if v["orderId"] == "O-1001")
    assert shipment["earliestAlternateArrival"] > data["request"]["requestedArrival"]
    assert shipment["guaranteed"] is False
    print("Fulfillment fixture: order states, inventory, and delivery constraint match the guide.")


def check_presales_sources():
    expected = {
        "customer-brief.txt": ["customer/brief.md", "customer-v1"],
        "product-knowledge.txt": ["product/capabilities.md", "product/reference-case.md", "product-v1"],
        "delivery-guide.txt": ["delivery/poc-guide.md", "delivery-v1"],
    }
    for filename, markers in expected.items():
        text = (EXAMPLES / "presales-team" / filename).read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                raise RuntimeError(f"Missing source marker {marker} in {filename}")
    print("Presales fixtures: required document paths and versions are present.")


if __name__ == "__main__":
    check_order_query()
    check_business_data()
    check_presales_sources()
    print("Local fixture validation only; no end-to-end scenario was executed.")
