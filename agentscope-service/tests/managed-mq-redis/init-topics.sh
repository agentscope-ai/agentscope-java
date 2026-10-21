#!/bin/sh
set -eu
for topic in ma_turns_v2 ma_runtime_events_v2 ma_protocol_turns_it_v2 ma_protocol_runtime_it_v2; do
  sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateTopic \
    -n namesrv:9876 -c ManagedMqTest -t "$topic" -r 8 -w 8 -a +message.type=FIFO
done
for group in ma_brain_v2 ma_hands_events_v2 ma_brain_it_v2 ma_hands_it_v2 ma_brain_protocol_it_v2 ma_hands_protocol_it_v2; do
  sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateSubGroup \
    -n namesrv:9876 -c ManagedMqTest -g "$group" -o true
done
