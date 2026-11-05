# skailify-adapters

Adapters that make existing apps agent-controllable in [Skaile](https://skaile.ai) ("Skailify").
Each adapter lives in a folder of its own, with its own build, tests and an `AGENTS.md` that a
coding agent can follow end to end to integrate it into an app. The contract between app and
platform is `@skaile/workspaces/app-sdk`.

| Adapter | Folder | What it does |
|---|---|---|
| vue-tsm | [`frontend/vue-tsm/`](frontend/vue-tsm/) | Vue 3 apps on the tsm module runtime: views, lists, UIModel forms and view actions over the Skaile app channel |
| emf-mcp | [`backend/emf-mcp/`](backend/emf-mcp/) | **Status: preliminary** (names and APIs may change; naming to be agreed with Data In Motion). EMF/OSGi backends: generic model tools over MCP, an OAuth resource server for Skaile's per-user MCP access, and the `/.well-known/skailify.json` manifest |

## License

Apache-2.0, see [LICENSE](LICENSE), unless a folder says otherwise: the Java sources in `backend/emf-mcp` are EPL-2.0 for now (see its README).
