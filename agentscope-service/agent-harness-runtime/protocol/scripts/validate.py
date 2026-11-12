#!/usr/bin/env python3
"""Offline contract/example checks. This is not a runtime or transaction simulator."""

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse

import rfc8785
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import OpenAPIV31SpecValidator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
OBJECT_LIMIT = 64 * 1024 * 1024
METADATA_LIMIT = 1024 * 1024
RETRY_CODES = {"execution_busy", "capacity_exceeded", "temporarily_unavailable"}
COUNTERS = {
    "epoch", "size", "sourceSeq", "throughSourceSeq", "byteLength", "offset",
    "nextOffset", "fromOffset", "toOffset", "ordinal", "expectedBrainRevision",
    "brainRevision", "sequence", "suspensionEpoch", "publicationSequence",
    "queueVersion", "fromSourceSeq", "inputTokens", "outputTokens",
    "cacheReadInputTokens", "cacheWriteInputTokens",
}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=unique_object)


def pointer(value, fragment):
    if not fragment:
        return value
    for key in fragment.lstrip("/").split("/"):
        key = key.replace("~1", "/").replace("~0", "~")
        value = value[int(key)] if isinstance(value, list) else value[key]
    return value


def example(ref):
    filename, _, fragment = ref.partition("#")
    return copy.deepcopy(pointer(read(ROOT / "examples" / filename), fragment))


SCHEMAS = {p.name: read(p) for p in sorted((ROOT / "schemas").glob("*.schema.json"))}
REGISTRY = Registry().with_resources(
    (s["$id"], Resource.from_contents(s)) for s in SCHEMAS.values()
)


def schema_ref(ref):
    filename, _, fragment = ref.partition("#")
    name = Path(urlparse(filename).path).name
    schema = SCHEMAS[name]
    pointer(schema, fragment)  # Also fail on unresolved fragments in unused definitions.
    return schema["$id"] + ("#" + fragment if fragment else "")


def schema_errors(ref, value):
    validator = Draft202012Validator(
        {"$ref": schema_ref(ref)}, registry=REGISTRY, format_checker=FormatChecker()
    )
    return list(validator.iter_errors(value))


def walk(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def protocol_walk(value):
    """Opaque inline tool/native/public payloads must not be interpreted as protocol records."""
    yield value
    if isinstance(value, dict):
        for key, child in value.items():
            if key != "inline":
                yield from protocol_walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from protocol_walk(child)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def frame(parts):
    return "".join(f"{len(p.encode('utf-16-le')) // 2}:{p}" for p in parts).encode("utf-8")


def fingerprint(*parts):
    return sha(frame(parts))


def scope_key(scope):
    parts = [scope[k] for k in ("tenant", "namespace", "sessionId")]
    return base64.urlsafe_b64encode(hashlib.sha256(frame(parts)).digest()).decode().rstrip("=")


class ContractError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition:
        raise ContractError(code)


def decoded(text):
    try:
        raw = base64.b64decode(text, validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ContractError("base64") from error
    require(base64.b64encode(raw).decode() == text, "base64")
    return raw


def unique(items, key):
    ids = [item[key] if isinstance(item, dict) else item for item in items]
    require(len(ids) == len(set(ids)), "duplicate_identity")


def semantics(value, definition):
    if definition in {"Envelope", "Command", "Event", "CommitRequest", "CoordinationRequest", "CoordinationResponse"}:
        return  # Java profiles retain numeric positions; generic counters remain strings.
    # JCS rejects non-I-JSON numbers and lone surrogates. Raw JSON parsing rejects duplicate keys.
    try:
        raw = rfc8785.dumps(value)
    except (ValueError, UnicodeError) as error:
        raise ContractError("canonical_json") from error
    limit = 90 * 1024 * 1024 if definition in {"ObjectWriteRequest", "ObjectReadResponse"} else METADATA_LIMIT
    if definition == "RecoveryBundle":
        limit = OBJECT_LIMIT  # Large bundles are stored by reference.
    if definition in {"InterestRequest", "PublishRequest"}:
        limit = 64 * 1024
    require(len(raw) <= limit, "payload_size")
    if not isinstance(value, dict):
        return
    scope = value.get("scope") or value.get("ref", {}).get("scope")
    if definition in {"ConfigurationResponse", "ModelBindingResponse"}:
        content = copy.deepcopy(value)
        digest = content["identity"].pop("configurationDigest")
        require(sha(rfc8785.dumps(content)) == digest, "configuration_digest")
    if definition == "ToolEvent" and value["type"] == "tool.result":
        result = value["data"]["result"]
        require((result["taskId"], result["executionId"]) == (value["taskId"], value["executionId"]), "result_binding")
    for node in protocol_walk(value):
        if not isinstance(node, dict):
            continue
        for key in COUNTERS & node.keys():
            if isinstance(node[key], str) and node[key].isascii() and node[key].isdigit():
                require(int(node[key]) <= 9223372036854775807, "counter_range")
        if {"backendId", "sha256", "size", "scope"} <= node.keys():
            require(scope is None or node["scope"] == scope, "object_scope")
            require(int(node["size"]) <= OBJECT_LIMIT, "object_size")
        if {"operationId", "taskId", "executionId", "nativeCallIds"} <= node.keys():
            require(node["executionId"] == node["taskId"], "execution_identity")
            if scope is not None and "turnId" in value:
                expected = "tool_" + fingerprint(scope_key(scope), value["turnId"], node["operationId"])
                require(node["taskId"] == expected, "task_identity")
            unique(node["nativeCallIds"], None)
        if {"resultId", "taskId", "executionId", "status"} <= node.keys():
            require(node["taskId"] == node["executionId"], "execution_identity")
            require(node["resultId"] == "result_" + fingerprint(node["taskId"], node["executionId"]), "result_identity")
            coverage = node["output"]
            if coverage["mode"] == "none":
                require(coverage["byteLength"] == "0", "output_coverage")
            unique(coverage.get("gapEventIds", []), None)
        if "resultIds" in node:
            unique(node["resultIds"], None)
    if definition in {"ObjectWriteRequest", "ObjectReadResponse"}:
        data = decoded(value["dataBase64"])
        ref = value.get("ref", value)
        require(len(data) == int(ref["size"]) and len(data) <= OBJECT_LIMIT, "object_size")
        require(sha(data) == ref["sha256"], "object_digest")
    if definition == "CommitBoundary":
        changes = value["changes"]
        for key, identity in [("toolCalls", "nativeCallId"), ("toolOperations", "operationId"), ("inputApplications", "inputId"), ("resultApplications", "nativeCallId")]:
            unique(changes.get(key, []), identity)
        unique(changes.get("toolOperations", []), "taskId")
        unique(value.get("publicFacts", []), "factId")
        if "suspension" in changes:
            unique(changes["suspension"]["pendingTaskIds"], None)
            unique(changes["suspension"]["pendingActionIds"], None)
    if definition == "ExecuteTool":
        require(value["messageId"] == "execute_" + value["operation"]["taskId"], "command_identity")
    if definition in {"CancelTool", "ReplayToolEvents", "ToolStateRequest", "ToolStateResponse"}:
        require(value["taskId"] == value["executionId"], "execution_identity")
    if definition == "CancelTool":
        require(value["messageId"] == "cancel_" + value["taskId"], "command_identity")
    if definition == "ReplayToolEvents":
        require(value["messageId"] == "replay_" + fingerprint(scope_key(scope), value["taskId"], value["requestId"]), "command_identity")
    if definition == "ToolEvent":
        require(value["taskId"] == value["executionId"], "execution_identity")
        expected = "tool_event_" + fingerprint(scope_key(scope), value["taskId"], value["executionId"], value["sourceSeq"])
        require(value["messageId"] == expected, "event_identity")
        data = value["data"]
        if value["type"] == "tool.result":
            result = data["result"]
            require((result["taskId"], result["executionId"]) == (value["taskId"], value["executionId"]), "result_binding")
            require(int(result["output"]["throughSourceSeq"]) == int(value["sourceSeq"])-1, "output_coverage")
        elif value["type"] == "tool.delta":
            data_bytes = decoded(data["dataBase64"])
            require(0 < len(data_bytes) <= 16384 and int(data["nextOffset"])-int(data["offset"]) == len(data_bytes), "output_offset")
        elif value["type"] == "tool.output_gap":
            require(int(data["toOffset"]) > int(data["fromOffset"]), "output_offset")
    if definition == "EventReceipt":
        require(value["taskId"] == value["executionId"], "execution_identity")
        if value["outcome"] == "buffered":
            require(not value["acceptedResultIds"], "buffered_result")
    if definition == "ToolStateResponse" and "result" in value:
        result = value["result"]
        require((result["taskId"], result["executionId"], result["status"]) == (value["taskId"], value["executionId"], value["state"]), "result_binding")
    if definition == "RecoveryResponse" and "bundle" in value:
        require(all(value[k] == value["bundle"][k] for k in ("scope", "turnId", "snapshotId")), "snapshot_identity")
        semantics(value["bundle"], "RecoveryBundle")
    if definition == "RecoveryBundle":
        require((value["brainRevision"] == "0") == ("nativeState" not in value), "recovery_state")
        for key, identity in [("toolOperations", "taskId"), ("acceptedResults", "resultId"), ("inputApplications", "inputId"), ("pendingInputs", "inputId")]:
            unique(value[key], identity)
    if definition == "Error":
        require(value["retryable"] == (value["code"] in RETRY_CODES), "error_retry")
        require(("retryAfterMs" in value) == value["retryable"], "error_retry")


def validate(ref, value):
    errors = schema_errors(ref, value)
    if errors:
        raise ValueError(f"{ref}: {errors[0].json_path}: {errors[0].message}")
    semantics(value, ref.rsplit("/", 1)[-1])


def patch(value, changes):
    for change in changes:
        parent, _, key = change["path"].rpartition("/")
        target = pointer(value, parent)
        key = int(key) if isinstance(target, list) else key
        if change["op"] == "remove":
            del target[key]
        elif isinstance(target, list) and change["op"] == "add":
            target.insert(key, copy.deepcopy(change["value"]))
        else:
            target[key] = copy.deepcopy(change["value"])
    return value


def check_specifications():
    for schema in SCHEMAS.values():
        Draft202012Validator.check_schema(schema)
        for node in walk(schema):
            if isinstance(node, dict) and "$ref" in node:
                schema_ref(node["$ref"])
    api = read(ROOT / "openapi.json")
    # Bundle local definitions in memory so the official validator never fetches a schema URL.
    bundled = copy.deepcopy(api)
    bundled["components"]["schemas"] = {
        filename.removesuffix(".schema.json") + "_" + name: definition
        for filename, schema in SCHEMAS.items() for name, definition in copy.deepcopy(schema["$defs"]).items()
    }
    for node in walk(bundled):
        if isinstance(node, dict) and "$ref" in node and ".schema.json" in node["$ref"]:
            schema_ref(node["$ref"])
            filename, fragment = node["$ref"].split("#")
            node["$ref"] = "#/components/schemas/" + Path(filename).name.removesuffix(".schema.json") + "_" + fragment.rsplit("/",1)[-1]
    errors = list(OpenAPIV31SpecValidator(bundled).iter_errors())
    if errors:
        raise ValueError(str(errors[0]))
    operations = [v for path in api["paths"].values() for v in path.values()]
    require(len({v["operationId"] for v in operations}) == len(operations), "operation_ids")
    for operation in operations:
        require("202" not in operation["responses"], "accepted_response")
    for node in walk(read(ROOT / "messages.json")):
        if isinstance(node, dict) and "$ref" in node:
            schema_ref(node["$ref"])
    return operations


def check_handoff():
    flow = read(ROOT / "examples/tool-handoff.json")
    for name, reply in [("toolCommit", "toolReceipt"), ("suspendCommit", "suspendReceipt"), ("applyResultCommit", "applyResultReceipt"), ("finishCommit", "finishReceipt")]:
        value, receipt = flow[name], flow[reply]
        require(receipt["contentHash"] == sha(rfc8785.dumps(value)), "receipt_digest")
        require(receipt["commitId"] == value["commitId"] and int(receipt["brainRevision"]) == int(value["expectedBrainRevision"])+1, "receipt_identity")
    operation = flow["toolCommit"]["changes"]["toolOperations"][0]
    require(flow["execute"]["operation"] == operation, "handoff_operation")
    require(flow["execute"]["authorizationCommitId"] == flow["toolReceipt"]["commitId"], "handoff_authorization")
    result = flow["resultEvent"]["data"]["result"]
    require(result == flow["recovery"]["bundle"]["acceptedResults"][0], "handoff_result")
    require(flow["applyResultCommit"]["changes"]["resultApplications"][0]["resultIds"] == [result["resultId"]], "handoff_application")
    require(flow["resultReceipt"]["contentHash"] == sha(rfc8785.dumps(flow["resultEvent"])), "receipt_digest")
    require(flow["resume"]["suspensionId"] == flow["suspendCommit"]["changes"]["suspension"]["suspensionId"], "handoff_resume")
    require(flow["takeoverReceipt"]["fence"] == flow["applyResultCommit"]["fence"], "handoff_owner")
    objects = read(ROOT / "examples/inputs-models-and-objects.json")
    bundle = json.loads(decoded(objects["recoveryObject"]["dataBase64"]))
    validate("recovery.schema.json#/$defs/RecoveryBundle", bundle)
    require(bundle == flow["recovery"]["bundle"], "snapshot_identity")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path)
    parser.add_argument("--schema", help="Local schema reference including #/$defs/Name")
    args = parser.parse_args()
    if args.instance or args.schema:
        if not (args.instance and args.schema):
            parser.error("--instance and --schema must be used together")
        validate(args.schema, read(args.instance))
        print("Instance passed schema and message-local semantic checks.")
        return
    operations = check_specifications()
    cases = read(ROOT / "examples/index.json")["cases"]
    for case in cases:
        validate(case["schema"], example(case["instance"]))
    # Every HTTP request/response and every channel variant needs at least one example.
    covered = {schema_ref(c["schema"]) for c in cases}
    for op in operations:
        for schema in (op["requestBody"]["content"]["application/json"]["schema"], op["responses"]["200"]["content"]["application/json"]["schema"]):
            require(schema_ref(schema["$ref"]) in covered, "missing_api_example")
    for node in walk(read(ROOT / "messages.json")):
        if isinstance(node, dict) and "$ref" in node:
            require(schema_ref(node["$ref"]) in covered, "missing_message_example")
    negatives = read(ROOT / "examples/negative-cases.json")["cases"]
    for case in negatives:
        value = patch(example(case["base"]), case["patch"])
        errors = schema_errors(case["schema"], value)
        if case["rejectedBy"] == "schema":
            require(bool(errors), "negative_accepted:" + case["name"])
        else:
            require(not errors, "negative_wrong_layer:" + case["name"])
            try:
                semantics(value, case["schema"].rsplit("/",1)[-1])
            except ContractError as error:
                require(error.code == case["code"], f"{case['name']}:expected_{case['code']}:got_{error.code}")
            else:
                raise ValueError("Negative example accepted: " + case["name"])
    vectors = read(ROOT / "examples/vectors.json")
    for vector in vectors["canonical"]:
        raw = rfc8785.dumps(vector["value"])
        require(raw.decode() == vector["canonical"] and sha(raw) == vector["sha256"], "canonical_vector")
    for vector in vectors["identity"]:
        raw = frame(vector["parts"])
        require(raw.hex() == vector["framedUtf8Hex"] and sha(raw) == vector["sha256"], "identity_vector")
        require(base64.urlsafe_b64encode(hashlib.sha256(raw).digest()).decode().rstrip("=") == vector["base64url"], "scope_vector")
    check_handoff()
    print(f"PASS: {len(SCHEMAS)} schemas, {len(operations)} API operations, {len(cases)} positive and {len(negatives)} negative cases; vectors and handoff links.")
    print("Offline contract checks only; database, MQ, ACK/crash recovery and engine compatibility are not tested.")


if __name__ == "__main__":
    main()
