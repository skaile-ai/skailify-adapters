<!-- SPDX-License-Identifier: Apache-2.0 -->

# skailify-adapters

Skailify makes an existing app agent-controllable in [Skaile](https://skaile.ai): an AI agent working in a
Skaile session can read what the app shows and act in it (open views, fill in forms, call the app's
backend) through the same contract the app's own users work with, and with the user's consent for
anything that changes data.

This repository holds the adapters that connect an app to Skaile. Each adapter lives in a folder of its
own, with its own build and tests and an `AGENTS.md` that a coding agent can follow end to end to
integrate it into an app.

- [`frontend/`](frontend/) holds adapters for UI frameworks: they expose an app's views, lists and forms
  over the Skaile app channel.
- [`backend/`](backend/) holds adapters for server runtimes: they expose an app's data and operations as
  tools over MCP.

The contract between an app and the platform is the app SDK, `@skaile/workspaces/app-sdk`, published in
the [`@skaile/workspaces`](https://www.npmjs.com/package/@skaile/workspaces) package.

## Adapters

| Adapter | Folder | Status | What it does |
|---|---|---|---|
| vue-tsm | `frontend/vue-tsm/` | In review | Vue 3 apps on the tsm module runtime: views, lists, UIModel forms and view actions over the Skaile app channel |
| emf-mcp | `backend/emf-mcp/` | In review, preliminary | EMF/OSGi backends: generic model tools over MCP, an OAuth resource server for per-user MCP access, and the `/.well-known/skailify.json` manifest |

"In review" means the adapter's pull request is open and not yet on `main`.

## Building

The JavaScript adapters share one [bun](https://bun.sh) workspace rooted here:

```sh
bun install
```

Java adapters build with their own committed wrapper (`./mvnw` or `./gradlew`), so they need only a
JDK 17 or later. Each adapter's README has its own build and test commands.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions need a DCO sign-off on every commit. Please follow
the [code of conduct](CODE_OF_CONDUCT.md), and report security problems as described in
[SECURITY.md](SECURITY.md).

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE), with one exception: `backend/emf-mcp/` is
EPL-2.0 and carries its own `LICENSE` file.
