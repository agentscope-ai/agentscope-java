---
title: "Automations and channels"
zh_link: /v2/zh/service/console/automation
---

<Note>
This documentation is a preview. The official release is not yet available.
</Note>

Once an Agent or Team can complete a task, arrange for it to run on a schedule or respond to external messages. **Work → Automations** manages schedules and webhook triggers. **Design → Channels** manages messaging connections, intake, and routing. Both use platform resource APIs; applications can configure them directly using the [Automation](/v2/en/service/automation) and [Channel](/v2/en/service/channels) guides.

## Schedule work

For a daily engineering digest, open **Work → Automations → New automation**. Write the Runbook first: explain what each run should read, how to summarize it, and how to handle missing information. Then select the executor and schedule.

| Field | Example |
| --- | --- |
| Name | Daily engineering digest |
| Runbook | Summarize recent project progress, cite sources, identify blockers and open questions, and deliver a digest |
| Context links | Project or document URLs, one per line |
| Assignee | An Agent or Team already tested for this work |
| Output mode | Create issue for collaboration and review; Run only for an automation execution record |
| Completion policy | Require human review when the result needs acceptance |
| Schedule / Time zone | `0 9 * * 1-5` / `Asia/Shanghai`, weekdays at 09:00 |

Context links supplies task material; the executor still needs tools and access to read those URLs. The console's Assignee form currently offers Agent and Team. Advanced action configurations in existing rules are edited through the API.

<Frame caption="Automation configuration with fixed demonstration data.">
  <img src="/imgs/service/automation.png" alt="Automation Runbook, executor, and trigger settings" />
</Frame>

Before saving, inspect **Next runs** to verify the time zone and future trigger times. Keep the rule disabled initially, save, and use **Test run** to inspect the output before enabling it. For enabled rules, the same action is labeled **Run now**. Both execute real work.

<Frame caption="Schedule preview showing upcoming triggers in the selected time zone.">
  <img src="/imgs/service/automation-schedule.png" alt="Cron schedule, time zone, and upcoming runs" />
</Frame>

## Follow runs and overlapping triggers

Open an execution under Runs to inspect its status, input, result, errors, and associated Issue. Use the rule's **Pause** to stop future triggers. To stop an existing execution, open that Run and use **Cancel run**.

Under Advanced settings, **Skip** in Overlapping runs skips a new trigger while an earlier execution occupies the rule; **Queue** processes runs in order. Maximum queue time limits waiting, while Maximum execution time limits execution. Choose according to the work: a digest may be skippable, while individual business events may need ordered processing.

## Trigger work from external events

Add a **Webhook** trigger in the Automation editor and set Events to accept if needed. After saving, copy the Webhook URL from the detail page and retain the secret shown on creation or rotation. Senders authenticate with `X-Automation-Secret` and use `Idempotency-Key` to identify events. See the [Automation API](/v2/en/service/automation) for requests.

Enable both the rule and its trigger before sending a test event. Inspect Webhook deliveries to check receipt and filtering, then Runs to check execution. Receiving an event and completing its work are separate stages. The Runbook should explain how to interpret the payload and what output is expected.

Before using **Replay** or **Run again** in run details, inspect the original payload, result, and business operations already performed. After rotating a secret, update the sender's configuration too.

This webhook lets an external system trigger work in Service. To have Service notify your backend when a session completes or needs input, use a [session webhook](/v2/en/service/session-event-log), which sends events in the other direction.

## Connect a messaging platform

Prepare the platform application and credentials. Open **Design → Channels → New channel**, choose a platform supplied by the deployment, fill its fields, set Default Agent and Conversation isolation, and create the connection. Configure callbacks or other connection details as required by the platform. Callback addresses must be reachable from that platform.

<Frame caption="Channel catalog with fixed demonstration data.">
  <img src="/imgs/service/channels.png" alt="Messaging Channel types and runtime status" />
</Frame>

In Channel details, use **Transfer rules → Add rule** to handle selected users, groups, or events with a chosen Agent. The default conversation target and transfer rules determine who receives messages. Related connections are also available under the Agent's **Connections → Channels**. Routing selects a logical Agent, not a particular runtime instance.

After saving, send a read-only request from a test account. Check receipt, target selection, and delivery of the response, then verify follow-up messages and files. A `running` status means the adapter has started; it does not verify the entire message path.

For work that needs follow-up, inspect the associated Issue and outbound status in the Channel's work intake and delivery area, then continue discussion and review in the [Issue](/v2/en/service/console/tasks). Even when the Agent succeeds, delivery can fail because of platform permissions or connection errors. Check the actual outbound record.

Use the [Agent API](/v2/en/service/service-api) or an [Endpoint](/v2/en/service/endpoints) to offer HTTP services to your applications. Channels connect and route messages from external messaging platforms.
