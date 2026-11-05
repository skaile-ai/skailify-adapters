# Skailify EMF/MCP adapter

> **Status: preliminary.** Bundle names, packages and APIs may still change. Contributed from
> Data In Motion's accounting pilot; the final naming is to be agreed with Data In Motion.

Makes an EMF/OSGi backend (Eclipse Fennec) reachable for Skaile agents:

- **`de.dim.skailify.emf.tools`**: generic MCP tools over any EPackage and Fennec
  repository: `describe_model`, `query`, `get`, `create`, `update`, `delete`, with structured
  validation errors and per-call read/write scopes.
- **`de.dim.skailify.mcp`**: an MCP server component for eclipse-fennec/emf.osgi-mcp
  that can be an OAuth resource server (RFC 6750, RFC 9728 metadata) and hands the verified
  caller to the tools; tool annotations (`readOnlyHint`, `destructiveHint`, …); static-token
  mode; the `X-Skaile-Preview-Authorization` fallback behind the Skaile preview proxy.
- **`de.dim.skailify.manifest`**: `/.well-known/skailify.json`, the manifest by which
  Skaile discovers the app, with a grant catalogue from the tools and the model's annotations.

The app plugs in through its EPackage, its repository and configuration; nothing here knows an
application. Integration step by step: [AGENTS.md](AGENTS.md).

Test support only (not part of the adapter): `de.dim.skailify.example.model` (a small
shop model), `de.dim.skailify.testing.filerepository` (a file/XMI repository so the tests
need no database) and `de.dim.skailify.tests` (OSGi integration tests over real HTTP).

## Build

Java 21:

```sh
./gradlew build
```

builds the bundles and runs the unit tests and the OSGi integration tests. Fennec dependencies
come as snapshots from Maven Central's snapshot repository (`cnf/ext/central.mvn`); see the
snapshot note in AGENTS.md.

## License

Apache-2.0, see [LICENSE](../../LICENSE).
