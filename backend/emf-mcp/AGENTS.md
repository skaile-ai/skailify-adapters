# AGENTS.md: integrate the Skailify EMF/MCP adapter into an OSGi backend

> **Status: preliminary.** Bundle names, packages and APIs may still change. Contributed from
> Data In Motion's accounting pilot; the final naming is to be agreed with Data In Motion.

Use the names as they are now and expect a rename later; keep the integration in one place
(run descriptor and configuration) so it is easy to follow.

You are a coding agent. Follow this file from top to bottom to make an existing EMF/OSGi
backend reachable for Skaile agents: generic model tools over MCP, an OAuth resource server
for Skaile's per-user MCP access, and the `/.well-known/skailify.json` manifest. Do every step,
run every check, and report what you could not verify.

## What it does

| Bundle | What it gives the app |
|---|---|
| `de.dim.skailify.mcp` | `SkailifyMCPServer` (factory PID `SkailifyMCPServer`): an MCP endpoint on the HTTP whiteboard, a drop-in for the upstream `HttpMCPServerComponent`, that can be an OAuth resource server (RFC 6750 challenges, RFC 9728 metadata, audience and scope checks) and hands the verified caller (`Caller`) to the tools. `SkailifyToolProvider`: collects tools and carries their annotations (`readOnlyHint`, `destructiveHint`, …) into the tool list. |
| `de.dim.skailify.emf.tools` | `SkailifyEmfTools` (factory PID): the tools `describe_model`, `query`, `get`, `create`, `update`, `delete` over one EPackage and one Fennec `Repository`. JSON in and out, filters as Fennec queries, OCL/Diagnostician validation with structured errors, unique attributes, read and write scopes. |
| `de.dim.skailify.manifest` | `SkailifyManifest` (factory PID): serves `/.well-known/skailify.json`: the app, its embed URL, the MCP endpoint, issuers and a grant catalogue built from the tools and the model's annotations. |

The test-only bundles (`…emf.example.model`, `…emf.testing.filerepository`, `…emf.tests`) are
not part of the adapter. Do not deploy them.

## Prerequisites

- Java 21, an OSGi framework with Declarative Services, Configuration Admin and the HTTP
  servlet whiteboard (the tests use Felix and Jetty 12).
- bnd (workspace or Maven/Gradle plugin) to build and resolve.
- EMF via Eclipse Fennec `emf.osgi` (the EPackage as an OSGi service with `emf.nsURI`).
- Eclipse Fennec `emf.osgi-mcp` (`org.eclipse.fennec.mcp.*`, MCP Java SDK) and, for OAuth,
  its `org.eclipse.fennec.mcp.auth.jwt` (`JwtTokenVerifier`).
- A Fennec persistence `Repository` service for the app's data
  (`org.eclipse.fennec.persistence.repository.api`), registered with `persistence.repository.id`.
- For OCL constraints: the Fennec m2x OCL engine and parser bundles.

## Step 1: build and add the bundles

`./gradlew build` in this folder builds the bundles and runs all tests. Add the three adapter
bundles to the app's run descriptor (`-runrequires` / `-runbundles`) and resolve. Keep one
emf.osgi runtime stack (see `de.dim.skailify.tests/test.bndrun` for a working set).

## Step 2: wire the tools to the app's model and repository

Configure through Configuration Admin (a configurator JSON, `.cfg` files or code). Minimal set:

```json
"SkailifyEmfTools~app": {
  "skailify.model": "app",
  "epackage.target": "(emf.nsURI=https://example.org/app/1.0)",
  "repository.target": "(persistence.repository.id=app)",
  "unique.attributes": ["Named.name"],
  "scope.read": "app:read",
  "scope.write": "app:write"
},
"SkailifyToolProvider~app": {
  "name": "app_tools",
  "description": "the app's model tools",
  "tools.target": "(skailify.model=app)",
  "tools.cardinality.minimum": 6
},
"SkailifyMCPServer~app": {
  "server.name": "app",
  "osgi.http.whiteboard.servlet.pattern": "/mcp",
  "osgi.http.whiteboard.target": "(id=app-http)",
  "has.tool.capability": true,
  "toolProviders.target": "(name=app_tools)"
}
```

Other tool options: `tool.prefix`, `types.excluded`, `messages.annotation.source` (a class
annotation holding a human message per constraint name), `structural.validation`,
`label.attributes`, `read.only`, `query.default.top`, `query.max.top`.

## Step 3: hide and describe through the model

Put an EAnnotation with source `https://data-in-motion.biz/skailify` on the EPackage, a class or a
feature:

| key | on | meaning |
|---|---|---|
| `expose` = `false` | class, feature | hidden from agents: the tools neither show nor accept it; a reference to a hidden class is hidden too |
| `description` | package, class, feature | what an agent should know, in place of the GenModel documentation |
| `grant` | class | the grant that covers changes to its objects (manifest) |
| `effectClass` | package, class | `routine`, `external` or `privileged` (manifest; package = default) |
| `approval` | package, class | `none`, `optional` or `required` (manifest; package default `required`) |

Hide secrets (credentials, bank details, internal notes) with `expose=false`. Check with
`describe_model` that they are gone.

## Step 4: a read-only endpoint

A second tool set over the same model with only the read tools, on its own endpoint:

```json
"SkailifyToolProvider~app-read": {
  "name": "app_read_tools",
  "description": "the app's read tools",
  "tools.target": "(&(skailify.model=app)(tool.readOnly=true))",
  "tools.cardinality.minimum": 3
},
"SkailifyMCPServer~app-read": {
  "server.name": "app-read",
  "osgi.http.whiteboard.servlet.pattern": "/mcp-read",
  "osgi.http.whiteboard.target": "(id=app-http)",
  "has.tool.capability": true,
  "toolProviders.target": "(name=app_read_tools)"
}
```

Each endpoint is its own OAuth resource (`<base>/mcp-read`); a token for it needs its audience.

## Step 5: authentication

Choose one mode per endpoint.

**OAuth resource server** (for Skaile's per-user MCP OAuth). Bind a `JwtTokenVerifier` to the
app's identity provider and switch the server on:

```json
"JwtTokenVerifier~app": {
  "verifier.name": "app",
  "jwks.url": "https://id.example.org/realms/app/protocol/openid-connect/certs",
  "issuer": "https://id.example.org/realms/app"
},
"SkailifyMCPServer~app": {
  "...": "as above",
  "verifier.target": "(verifier.name=app)",
  "oauth.enabled": "true",
  "public.url": "https://app.example.org",
  "oauth.authorization.servers": ["https://id.example.org/realms/app"],
  "oauth.scopes.supported": ["app:read", "app:write"],
  "oauth.scopes.required": ["app:read"]
}
```

- A request without a token gets `401` with `WWW-Authenticate: Bearer …, resource_metadata="<base>/.well-known/oauth-protected-resource/mcp"`;
  the metadata (RFC 9728) names the authorization servers and scopes. That is how an MCP client
  finds where to get a token.
- A token must verify (signature, issuer, expiry), carry the resource (`<base>/mcp`, or
  `oauth.resource`) or one of `oauth.audiences` in `aud`, else `401 invalid_token`; it must
  grant `oauth.scopes.required`, else `403 insufficient_scope`.
- `scope.read` / `scope.write` of the tools are checked per call: a read token reads but does not
  write.
- With OAuth on and no verifier bound, the endpoint answers `503`; it is never open.
- `public.url` fixes the base URL; empty, it is taken from the request (`Host`,
  `X-Forwarded-*`). Behind a proxy that rewrites the host, set it.
- Every tool gets the verified `Caller` (subject, username, client, scopes, token). Log changes
  with it. Authorization against the app's own data stays the app's job.

**Static token** (OAuth off): `auth.token` on the server; every request needs
`Authorization: Bearer <token>`. Without a token configured only loopback callers get in.

**Behind the Skaile preview proxy:** the proxy drops `Authorization`. A client sends the app's
token as `X-Skaile-Preview-Authorization: Bearer <token>` instead; the adapter reads it when
`Authorization` is absent, in both modes. A real `Authorization` header always wins.

## Step 6: the manifest

```json
"SkailifyManifest~app": {
  "app.id": "app",
  "app.name": "Example App",
  "oauth.enabled": "true",
  "auth.issuers": ["https://id.example.org/realms/app"],
  "auth.scopes": ["app:read", "app:write"],
  "tools.target": "(skailify.model=app)",
  "actions": ["{\"name\": \"app.app.approve\", \"approval\": \"optional\"}"],
  "osgi.http.whiteboard.target": "(id=app-http)"
}
```

`GET /.well-known/skailify.json` then lists the app, `embedUrl`, `mcp.url`, the auth block with
the protected resource metadata URL, and one grant per tool (kind, effect class, approval,
scope; for change tools one entry per visible class with a `grant`). `actions` adds the app's
`app.*` capabilities of the UI or a server-side connection. The format is a draft (`0.1-draft`).

## Step 7: verify

1. `./gradlew build` here: unit tests and the OSGi integration tests
   (`de.dim.skailify.tests`) pass.
2. In the app's runtime:
   - `curl -i -X POST <base>/mcp` without a token: `401` with `resource_metadata` (OAuth) or
     `401`/`403` (static token / loopback only).
   - `curl <base>/.well-known/oauth-protected-resource/mcp` and
     `curl <base>/.well-known/skailify.json`: valid JSON with your issuer, scopes and tools.
   - An MCP client with a valid token: `tools/list` shows the six tools with annotations;
     `describe_model` hides what you hid; `query`, `create` with a constraint violation (structured
     `constraint_violation`), a write with a read-only token (`insufficient_scope`).
   - The same through the Skaile preview URL with `X-Skaile-Preview-Authorization`.
3. Report exactly which of these you ran and what happened.

## Known limits

- **Snapshots.** Fennec comes as snapshots from Maven Central's snapshot repository
  (`cnf/ext/central.mvn`). A snapshot can change under a build: on 2026-11-05 a Data Atlas
  snapshot moved types into a new artifact and broke code generation of models extending them
  overnight. Snapshots also expire after a while. Pin what you depend on, or move to releases
  when they exist; when a clean build fails, compare the resolved snapshot timestamps first.
- **Private egress (skaile-ai/platform#9035).** Skaile reaches the MCP endpoint and the
  manifest from its own network: an app reachable only inside a private network is not
  reachable for Skaile agents.
- **Failed preview builds (skaile-ai/platform#9248).** A preview whose build fails keeps
  serving the previous image, so a broken change can look like a working old one. Check the
  build log after each change.
- **Upstream gaps.** `SkailifyMCPServer` and `SkailifyToolProvider` re-do parts of
  `emf.osgi-mcp` (tool annotations, caller context, resource metadata;
  eclipse-fennec/emf.osgi-mcp#55–#59). Once upstream has them, they go.
- The EAnnotation source `https://data-in-motion.biz/skailify` is the contract with the model. Like
  the names, it may change with the final naming.
