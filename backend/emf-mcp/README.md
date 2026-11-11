# Skailify EMF/MCP adapter

> **Status: preliminary.** Bundle names, packages and APIs may still change. Contributed from
> Data In Motion's accounting pilot; the final naming is to be agreed with Data In Motion. License:
> EPL-2.0.

Makes an EMF/OSGi backend (Eclipse Fennec) reachable for Skaile agents:

- **`de.dim.skailify.emf.tools`**: generic MCP tools over any EPackage and Fennec
  repository: `describe_model`, `query`, `get`, `create`, `update`, `delete`, with structured
  validation errors and per-call read/write scopes. The write tools are served only with an
  explicit opt-in (see "Writes and consent").
- **`de.dim.skailify.mcp`**: an MCP server component for eclipse-fennec/emf.osgi-mcp
  that can be an OAuth resource server (RFC 6750, RFC 9728 metadata) and hands the verified
  caller to the tools; tool annotations (`readOnlyHint`, `destructiveHint`, …); static-token
  mode; the `X-Skaile-Preview-Authorization` fallback behind the Skaile preview proxy.
- **`de.dim.skailify.manifest`**: `/.well-known/skailify.json`, a manifest describing the app,
  with a grant catalogue from the tools and the model's annotations. Skaile does not import it
  yet (skaile-ai/platform#9034); discovery through it is future-facing.

The app plugs in through its EPackage, its repository and configuration; nothing here knows an
application. Integration step by step: [AGENTS.md](AGENTS.md).

## Writes and consent

`create`, `update` and `delete` are MCP tools like the read tools. **The Skaile platform does not
apply its approval cards or any user consent to MCP calls**: an agent with a write token changes
data directly, as the user. The `approval` and `effectClass` values in the model's annotations and
in the manifest are declarations only; nothing enforces them on an MCP call today.

So the write tools are **off by default**: an MCP server serves only tools that declare
`readOnlyHint=true` (`describe_model`, `query`, `get`) unless its configuration sets
`"write.tools": true`. With that opt-in on, `create`, `update` and `delete` are served as well,
**ungoverned and your responsibility**: no Skaile approval or consent applies, and the server logs
a WARN saying so when it activates. Expose endpoints without the opt-in to agents, and put writes
that need consent through `app.*` actions of the UI adapter (`frontend/vue-tsm`), where the
platform's approval applies. The platform design treats MCP as the
read and query channel; that may change or remove the write tools here.

Test support only (not part of the adapter): `de.dim.skailify.example.model` (a small
shop model), `de.dim.skailify.testing.filerepository` (a file/XMI repository so the tests
need no database) and `de.dim.skailify.tests` (OSGi integration tests over real HTTP).

## Build

Java 21:

```sh
./gradlew build
```

builds the bundles and runs the unit tests and the OSGi integration tests.

## Dependencies

Released artifacts come from Maven Central (`cnf/ext/central.mvn`). Most of the Fennec artifacts
this adapter needs have no release yet, so they are pinned to exact snapshot builds that build
and pass the tests as of 2026-11-05: `cnf/ext/fennec-pinned.xml` is an OSGi repository index
whose entries point at the timestamped jars in Maven Central's snapshot repository, each checked
by SHA-256. A new snapshot never comes in by itself.

Still snapshots, and why:

| Project | Version line | Pinned builds | Why not a release |
|---|---|---|---|
| Fennec EMF (`org.eclipse.fennec.emf`): `osgi.api`, `osgi.component.minimal`, `osgi.metadata`, `osgi.eobject.registry`, `osgi.codegen`, `osgi.bnd.library.project` | 1.1.0 | 2026-10-31 | 1.0.2 is released, but Fennec MCP, Persistence and Codec are built against the 1.1.0 line; mixing lines puts two EMF OSGi lines into one runtime |
| Fennec MCP (`org.eclipse.fennec.mcp`): `api`, `endpoint`, `http.component`, `auth.jwt` | 0.1.0 | 2026-10-06 | no release yet |
| Fennec Codec (`org.eclipse.fennec.codec`): `api`, `metadata` | 0.1.0 | 2026-10-30 | no release yet |
| Fennec Persistence (`org.eclipse.fennec.persistence`): `persistence`, `capabilities`, `repository`, `query`, `query.derived`, `pushstreams`, `command.model`, `expression.model`, `expression.ocl`, `query.model`, `stream.model`, `tck` (tests only) | 0.1.0 | 2026-11-05 | no release yet |
| Fennec M2X (`org.eclipse.fennec.m2x`): `unit`, `ocl.api`, `ocl.model`, `ocl.parser`, `ocl.engine` | 0.1.1 | 2026-10-31 | no release yet |

Central deletes old snapshot builds after a while. When a build fails to download a pinned jar,
move the pins: build with the snapshots that are current, check the tests, regenerate the index
with `bnd index` over those jars and point its `url` entries at the timestamped jars. Move to
releases as soon as they exist. Fennec's workspace libraries (`fennecEMF`, `fennecMCP`,
`fennecPersistence`) are not used, because their indexes name open `-SNAPSHOT` versions.

## License

This folder is EPL-2.0: every source file carries a Data In Motion EPL-2.0 header
(`SPDX-License-Identifier: EPL-2.0`), and the license text is in [LICENSE](LICENSE). The rest
of the repository is Apache-2.0, see [LICENSE](../../LICENSE). The Gradle wrapper scripts keep their own Apache-2.0 headers.
