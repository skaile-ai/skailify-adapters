<!-- SPDX-License-Identifier: Apache-2.0 -->

# Frontend adapters

Adapters for UI frameworks. A frontend adapter runs inside the app's browser bundle and, when the app is
embedded in a Skaile session, exposes what the app shows (its views, lists, forms and actions) to the
agent over the Skaile app channel defined by `@skaile/workspaces/app-sdk`.

Each adapter is a folder here (`frontend/<name>/`) with:

- a `package.json` (it joins the bun workspace at the repository root, so `bun install` at the root
  installs it),
- a `README.md` covering what it exposes and how to build and test it,
- an `AGENTS.md` a coding agent can follow to integrate the adapter into an app,
- its tests, runnable on their own from the adapter's folder.

Frontend adapters are Apache-2.0 licensed. See the [root README](../README.md) for the list of adapters
and [CONTRIBUTING.md](../CONTRIBUTING.md) for how to add one.
