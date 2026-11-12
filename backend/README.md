<!-- SPDX-License-Identifier: Apache-2.0 -->

# Backend adapters

Adapters for server runtimes. A backend adapter runs inside the app's server and exposes its data and
operations to the agent as tools over MCP, together with whatever the platform needs to reach them on
the user's behalf (for example an OAuth resource server and a `/.well-known/skailify.json` manifest).

Each adapter is a folder here (`backend/<name>/`) with its own build, a `README.md` covering what it
exposes and how to build and test it, and an `AGENTS.md` a coding agent can follow to integrate it.
A Java adapter commits its build wrapper (`./mvnw` or `./gradlew`), so building it needs only a JDK 17
or later.

Backend adapters are Apache-2.0 licensed, except `backend/emf-mcp/`, which is EPL-2.0 and carries its
own `LICENSE`. See the [root README](../README.md) for the list of adapters and
[CONTRIBUTING.md](../CONTRIBUTING.md) for how to add one.
