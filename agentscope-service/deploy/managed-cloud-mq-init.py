#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Initialize Alibaba Cloud LITE resources through the logged-in aliyun CLI.

Existing incompatible resources are never deleted or silently reconfigured.
This script initializes management resources; credentials remain in Secrets.
"""
import argparse
import json
import os
import subprocess

PAIRS = [
    ("ma_turns_v3", "ma_brain_v3"),
    ("ma_tool_execute_v1", "ma_hands_execute_v1"),
    ("ma_tool_control_v1", "ma_hands_control_v1"),
    ("ma_tool_events_v3", "ma_brain_tool_events_v3"),
    ("ma_turn_resumes_v3", "ma_brain_resumes_v3"),
]


def initialize(instance, region):
    def call(action, *args, missing=None):
        result = subprocess.run(
            ["aliyun", "rocketmq", action, "--region", region,
             "--instanceId", instance, *args], capture_output=True, text=True, check=False
        )
        value = json.loads(result.stdout or result.stderr)
        if result.returncode:
            if missing and value.get("error_code") == missing:
                return None
            raise RuntimeError(json.dumps(value, ensure_ascii=False))
        if value.get("success") is not True:
            raise RuntimeError(json.dumps(value, ensure_ascii=False))
        return value.get("data")

    keys = [('TURN_TOPIC', 'BRAIN_GROUP'), ('TOOL_EXECUTE_TOPIC', 'TOOL_EXECUTE_GROUP'),
            ('TOOL_CONTROL_TOPIC', 'TOOL_CONTROL_GROUP'), ('TOOL_EVENT_TOPIC', 'TOOL_EVENTS_GROUP'),
            ('RESUME_TOPIC', 'RESUME_GROUP')]
    for (topic, group), (topic_key, group_key) in zip(PAIRS, keys):
        topic = os.environ.get('BUILDER_MANAGED_MQ_' + topic_key) or topic
        group = os.environ.get('BUILDER_MANAGED_MQ_' + group_key) or group
        existing = call("GetTopic", "--topicName", topic, missing="Topic.NotFound")
        if existing is None:
            call("CreateTopic", "--topicName", topic, "--body", json.dumps({
                "messageType": "LITE", "liteTopicExpiration": -1,
                "remark": "AgentScope Service " + topic,
            }))
        elif existing.get("messageType") != "LITE":
            raise RuntimeError("Existing Topic must be LITE: " + topic)
        existing = call("GetConsumerGroup", "--consumerGroupId", group,
                        missing="ConsumerGroup.NotFound")
        if existing is None:
            call("CreateConsumerGroup", "--consumerGroupId", group, "--body", json.dumps({
                "deliveryOrderType": "Orderly",
                "consumeRetryPolicy": {"retryPolicy": "FixedRetryPolicy",
                                       "fixedIntervalRetryTime": 1, "maxRetryTimes": 16},
                "messageModel": "CLUSTERING", "topicName": topic,
                "remark": "AgentScope Service verified wildcard Lite consumer",
            }))
        elif (existing.get("messageModel") != "CLUSTERING"
              or existing.get("topicName") != topic
              or existing.get("deliveryOrderType") != "Orderly"):
            raise RuntimeError("Incompatible consumer group; drain and review before replacing: " + group)
        print(topic + " -> " + group + " configured")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True)
    parser.add_argument("--region", required=True)
    options = parser.parse_args()
    initialize(options.instance, options.region)
