# AGENTS.md: integrate `@skaile/skailify-vue-tsm` into a Vue/tsm app

You are a coding agent. Follow this file from top to bottom to make an existing Vue 3 app
agent-controllable inside a Skaile session. Do every step, run every check, and report what
you could not verify.

## What it does

A Skaile agent works the app the way its user does, over the Skailify **app channel**: it
reads what the user sees and acts through the app's own UI code. The adapter registers these
capabilities with the Skaile gateway (the agent sees each one as `app.<appId>.<name>`):

| Name | Kind | What it does |
|---|---|---|
| `list_views` | query | The app's views: id, title, in the menu or not, the object types each one opens |
| `get_view_state` | query | Current view and selection, visible list rows, open forms with values, empty required fields, view messages, the actions available now |
| `navigate` | effect | Show a view, optionally with an object selected |
| `open` | effect | Open an object by type and id, in the view that declares that type |
| `select` | effect | Select a visible list row, as a click does |
| `form.set_fields` | effect | Write form fields through the UIModel's Ecore bindings; each refused field comes back with its reason |
| `form.submit` | effect | Save a form through the view's own save |

Plus every action a view registers itself (`registerAction`).

It connects only when the app runs embedded in Skaile as a `protocol` preview app (an iframe
whose URL carries `skaileParentOrigin`). Opened on its own, the app does not connect and every
registration is a no-op in cost.

## Prerequisites

- Node.js 22 or newer.
- Vue 3.5+, vue-router 5, zod 4.
- `@skaile/workspaces` ^7.18.1 (the contract: `@skaile/workspaces/app-sdk`).
- Forms built from UIModels with `@emfts/core` and `@emfts/uimodel-composer`.
- Optional: the tsm module runtime (`@eclipse-daanse/tsm`), if the views come from tsm view
  services. Without it, pass your own view list.
- The app is declared as a preview app in the Skaile session's `skaile.preview.json`; its `id`
  there is the `appId` below.

All of these are peer dependencies: install them in the app, not in this package.

`@emfts/core`, `@emfts/uimodel-composer` and `@eclipse-daanse/tsm` have only prereleases on npm
so far. Their peer ranges are tilde ranges on the tested prerelease (`~0.3.0-next.4`,
`~0.0.2-next.1`, `~0.1.0-next.1`): later prereleases and patch releases of that minor version
match, the next minor version does not. Move to caret ranges once they are stable 1.x.

## Step 1: install

Add the package (from this repository, until it is published) and its peers to the app. In an
app whose views are separate bundles, serve the package **once** through the import map as a
shared library, like `vue`, so that the shell and every view bundle share one registry. Two
copies mean two registries, and the agent sees nothing a view registers.

## Step 2: connect once, in the shell

The shell owns the router. Call `startSkailify` once after the router exists:

```ts
import { startSkailify, tsmViewSource } from '@skaile/skailify-vue-tsm'

startSkailify({
  appId: 'shop',                                  // the id in skaile.preview.json
  views: tsmViewSource(context, 'shop.view'),     // or () => ViewDescriptor[]
  router,
  resolveReference: (type, idOrLabel) => findLoaded(type, idOrLabel), // for reference fields
  onStatus: (status) => console.debug('skailify', status.state),
})
```

- `views` is called on every request. Each view needs an `id` that is also its path (`orders`
  is shown under `/orders`), a `title`, `menu`, and `objectTypes` (EClass names it opens).
  `tsmViewSource` reads them from the tsm view services' `view`, `order`, `menu` and
  `objectTypes` properties and the service's `title`; rename them through `TsmViewContract`.
- `selectionParam` (default `id`) is the query parameter a view reads the selected object from.
- Other options: `label`, `write`, `maxRows` (default 50), `settleTimeoutMs` (default 3000,
  at most 10000), `capabilityPrefix` (leave empty; the platform already namespaces by app id).

## Step 3: register what each view shows

Call these in the **setup** of the view component. They are removed with the component.

```ts
import { useAgentForm, useAgentList, registerAction, z } from '@skaile/skailify-vue-tsm'

useAgentList({
  id: 'orders',
  rows: () => shown.value,               // as shown: filtered and sorted
  total: () => all.value.length,
  select: (row) => open(row),            // what a click on a row does
  visible: () => !selectedId.value,      // hidden behind a detail?
  loading: () => loading.value,          // true while the rows are fetched (step 5)
})

useAgentForm({
  uiModel,                               // the uimodel:UIModel EObject the composer renders
  model: () => editing.value,            // null while no object is open
  title: 'Order',
  submit: async () => (await save()) ? true : { ok: false, message: error.value },
  messages: () => [error.value],
  loading: () => !editing.value && !error.value,
})
```

## Step 4: view-specific actions

```ts
registerAction({
  name: 'orders.approve',                // relative; the agent sees app.<appId>.orders.approve
  displayName: 'Approve an order',
  description: 'Approve the order, as the Approve button does.',
  kind: 'effect',
  requiresApproval: true,
  effectClass: 'routine',
  input: z.object({ orderId: z.string() }),
  handler: async ({ orderId }, ctx) => {
    audit(ctx, 'orders.approve', { orderId })
    return approve(orderId)
  },
})
```

Rules:

- **Per-view scoping.** Register actions in the setup of the view that offers them, never when a
  bundle or module activates. A bundle stays active while other views are shown; its actions
  would then be offered everywhere.
- **effectClass and requiresApproval.** `effectClass` is `routine` (default), `external`,
  `privileged` or `never`. The platform runs the action at least that strict, or stricter by the
  project's floor. A class above `routine` needs `requiresApproval: true`; an action that breaks
  this, or names an unknown class, is **not registered** and an error is logged (the gateway
  would refuse it anyway). Anything that changes data the user would want to confirm needs
  `requiresApproval: true`.
- **Principal and audit.** Every handler gets `ctx.principal`: whose agent turn made the call
  (`{ kind: 'human', userId }`, `trigger`, `mixed` or `unknown`; for an approved action, the turn
  that proposed it). It is not for authorization: the app acts as its own logged-in user. Call
  `audit(ctx, action, details)` in every handler that changes something; it writes one line with
  the principal to the session log. `form.submit` does so itself.
- Handlers that navigate use `navigateTo(view, objectId?, wait?)`; `skailifyBridge()?.viewState()`
  gives the state to return.

## Step 5: loading signals

The adapter sees views register, not their data arrive. A list or form that fetches
asynchronously **must** pass `loading`, or the agent may read it empty:

```ts
const loading = shallowRef(true)          // true from the start: the view loads when mounted
async function reload() {
  loading.value = true
  try { rows.value = await fetchRows() } finally { loading.value = false }
}
```

`navigate`, `open`, `select` and `get_view_state` wait until no visible list or form is loading
(up to the settle timeout). Each list state carries `loading`. A result that timed out, or whose
lists are still loading, carries `settled: false`: the agent reads again instead of trusting an
empty list. For views without a signal the agent can pass `wait` (`loaded` default,
`registered`, `none`), `waitFor: { list, minRows, form }` and `timeoutMs` (at most 10 s). Do not
add a "rows stopped changing" heuristic; it cannot tell a slow list from an empty one.

## Step 6: verify

1. In this package: `npm ci && npm test && npm run type-check`.
2. In the app: its type-check and build pass; opened standalone it works as before and reports
   `standalone` through `onStatus`.
3. Tests of your own: drive the adapter against the fake gateway (`test/fake-host.ts` shows the
   pattern: it answers the handshake, records registrations and invokes capabilities as the
   agent does). Cover at least: a view whose rows arrive late (`settled: true` with rows), one
   that never loads (`settled: false`), and that leaving a view removes its actions.
4. In a Skaile session with the app as a preview: `onStatus` reaches `connected`; an agent
   calls `list_views`, `navigate` to a list view (rows, `settled: true`), `get_view_state`,
   `form.set_fields` and `form.submit` on a form, and one of your actions with approval. Check
   that an action of view A is gone after navigating to view B.

Report exactly which of these you ran and what happened.

## Known limits

- The platform registers capabilities by name per session; two apps in one session are kept
  apart by the app id the platform puts in front of each name.
- A preview whose build fails keeps serving the previous image (skaile-ai/platform#9248), so a
  broken change can look like a working old one. Check the preview build log after each change.
- The preview proxy drops `Authorization` and cookies. If the app's backend calls need the app's
  own bearer token through the preview URL, send it as `X-Skaile-Preview-Authorization: Bearer
  <token>` (skaile-ai/platform#9264); the backend reads it when `Authorization` is absent.
- No conformance kit yet; one is planned in `@skaile/workspaces/app-sdk`.
