<!-- SPDX-License-Identifier: Apache-2.0 -->

# Contributing

Thanks for helping. Bug reports, fixes and new adapters are all welcome.

## Before you start

- For a bug, open an issue with the adapter, its version (or commit) and the steps to reproduce it.
- For a new adapter or a larger change, open an issue first so we can agree on the shape before you
  write it.
- Security problems go to security@skaile.ai, not to an issue; see [SECURITY.md](SECURITY.md).
- Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Developer Certificate of Origin (DCO)

This project uses the [Developer Certificate of Origin 1.1](https://developercertificate.org/) instead
of a contributor licence agreement. By signing off a commit you certify that you wrote it, or otherwise
have the right to submit it under the licence of the files it touches.

Every commit needs a `Signed-off-by` line with your real name and an email address you can be reached
at. Git adds it for you:

```sh
git commit -s -m "fix(vue-tsm): keep the selection after a list refresh"
```

To sign off commits you already made on your branch:

```sh
git rebase --signoff origin/main
git push --force-with-lease
```

A pull request is merged only when every commit in it is signed off.

### Commits written by a coding agent

A commit written by a coding agent is signed off like any other. The maintainer who approves the pull
request's merge certifies it under the DCO, having reviewed the change: a person, not the agent, is
always the one who certifies.

## License headers

Every new source file starts with an SPDX licence identifier, in the comment syntax of the file:

```ts
// SPDX-License-Identifier: Apache-2.0
```

```md
<!-- SPDX-License-Identifier: Apache-2.0 -->
```

Use `Apache-2.0` everywhere except in `backend/emf-mcp/`, which is EPL-2.0 and carries its own `LICENSE`;
files there use `SPDX-License-Identifier: EPL-2.0`. Files that cannot hold a comment (JSON, lockfiles)
take the licence of the folder they are in.

## Adding an adapter

1. Put it in `frontend/<name>/` (UI frameworks) or `backend/<name>/` (server runtimes).
2. Give it a `README.md` (what it exposes, how to build and test it) and an `AGENTS.md` a coding agent
   can follow to integrate it into an app.
3. A JavaScript adapter has a `package.json` and joins the bun workspace; `bun install` at the
   repository root installs it. A Java adapter commits its own build wrapper (`./mvnw` or `./gradlew`),
   so it builds with only a JDK 17 or later.
4. Keep it app-agnostic: the app passes in what is specific to it.
5. Add a row to the adapter table in the [root README](README.md).

## Pull requests

- One change per pull request, with tests for what it changes.
- Title it `<type>(<adapter>): <description>`, with `<type>` one of `feat`, `fix`, `refactor`, `docs`,
  `test`, `chore`, `perf` or `build`; use `repo` as the scope for changes outside an adapter.
- Make sure the adapter's build and tests pass locally before you open it.
- A maintainer reviews it; a person, never an automated tool, merges it.
