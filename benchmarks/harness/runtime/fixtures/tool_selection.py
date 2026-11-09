# Copyright (c) LangChain, Inc.
# SPDX-License-Identifier: MIT
# Attribution and license: see LICENSE and the capabilities dataset README.
# Independently maintained synthetic tools; no upstream test framework dependency.
from __future__ import annotations

from runtime.fixtures import tool


@tool
def slack_send_dm(user_id: str, message: str) -> str:
    """Send a direct message to a user on Slack."""
    return f"Sent DM to {user_id}: {message}"


@tool
def slack_post_channel(channel: str, message: str) -> str:
    """Post a message to a Slack channel."""
    return f"Posted to #{channel}: {message}"


@tool
def github_create_issue(repo: str, title: str, body: str) -> str:
    """Create a new GitHub issue."""
    return f"Created issue '{title}' in {repo} — {body}"


@tool
def github_create_pr(repo: str, title: str, head: str, base: str) -> str:
    """Create a pull request on GitHub."""
    return f"Created PR '{title}' in {repo} ({head} -> {base})"


@tool
def linear_create_issue(team: str, title: str, description: str) -> str:
    """Create a new issue in Linear."""
    return f"Created Linear issue '{title}' in {team} — {description}"


@tool
def gmail_send_email(to: str, subject: str, body: str) -> str:
    """Send an email via Gmail."""
    return f"Sent email to {to}: {subject} — {body}"


@tool
def web_search(query: str) -> str:
    """Search the web for information."""
    return (
        f"Top 3 results for {query!r}:\n"
        f"1. Official documentation page covering the topic.\n"
        f"2. Recent blog post discussing updates and features.\n"
        f"3. Community discussion thread with examples."
    )


@tool
def calendar_create_event(title: str, date: str, attendees: list[str]) -> str:
    """Create a calendar event."""
    return f"Created event '{title}' on {date} with {', '.join(attendees)}"
