# Automation and channels

Verify a manual workflow before triggering the same work from schedules or external events.

## Automation

Create an Automation and choose its trigger and supported Agent, Team or orchestration target. Run it manually once and inspect inputs, resource permissions, execution records and deliverables before enabling recurring execution.

Specify scope, output location and expected behavior on repeated runs. When a run fails, locate its Run or Task before retrying. Avoid creating duplicate schedules for the same work.

## Channels

Configure a platform connection and Agent association in Channels. Platform credentials, callback URLs, message routing and user identity pairing have different responsibilities. Remote callbacks require a reachable HTTPS endpoint; OAuth callbacks also require the correct public service URL.

Send a test message and inspect the resulting work, authorization and reply delivery. Pair user identities where the channel workflow requires it. Profile provides personal connection and subscription management.

Message formats and capabilities differ by channel. Use its configuration screen and verified behavior rather than assuming all channels implement identical webhook events.

## Operational checks

Check Scheduler health, control-plane connectivity, credential validity and third-party reachability of callback paths. After rotating credentials, verify the complete inbound and outbound path again.

See [Configuration](configuration.md) and [Permissions](access.md).
