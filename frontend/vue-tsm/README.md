# @skaile/skailify-vue-tsm

Makes a Vue 3 app on the tsm module runtime agent-controllable inside a Skaile session, over the Skailify app channel
(`@skaile/workspaces/app-sdk`). It contains no app-specific code: the app passes in its view registry,
its router and its app id, and its views register what they show.

It connects only when the app is embedded in Skaile as a `protocol` preview app: an iframe whose URL
carries `skaileParentOrigin`. Opened on its own, the app does not connect, and registrations cost nothing.

## Capabilities

The agent sees them as `app.<appId>.<name>`, because the platform puts the app id in front of each name
(skaile-ai/platform#9031). `capabilityPrefix` adds a segment of your own if you need one.

| Name | Kind | What it does |
|---|---|---|
| `list_views` | query | The views from the registry: id, title, whether they are in the menu, and the object types they open |
| `get_view_state` | query | The current view and selection, the visible list rows (ids and labels, capped), the open forms with field values, empty required fields and view messages, and the actions available now |
| `navigate` | effect | Show a view, optionally with `?<selectionParam>=<id>` |
| `open` | effect | Open an object by type and id, in the view that declares that type |
| `select` | effect | Select a visible list row, the view's own way or through the route |
| `form.set_fields` | effect | Write field values through the UIModel's Ecore feature bindings. Each field is checked on its own, and refused fields come back as `errors: [{ field, message }]` |
| `form.submit` | effect | Save the form through the view's save. Empty required fields or the app's refusal come back as structured errors |

Views add their own actions with `registerAction` (a relative name, Zod input, `requiresApproval` for
anything that needs the user's consent). Register them in the view's setup, not when the bundle
activates: they are removed with the view, so other views do not offer them.

### Waiting for a view

The registry only sees views register and unregister, not their data arriving. So views report it:
a list or form passes `loading: () => boolean`, true while it fetches. `navigate`, `open`, `select`
and `get_view_state` wait (up to the settle timeout) until no visible list or form is loading, and
every list state carries `loading`. A view without the signal is waited for until it has registered.
The agent can choose per call:

- `wait`: `"loaded"` (default), `"registered"` or `"none"`
- `waitFor: { list?, minRows?, form? }`: until that list shows at least `minRows` rows (any list when
  unnamed) and that form is open; for views without a loading signal
- `timeoutMs`: at most 10 s

A timeout is no error: the state comes back as it is, with `settled: false`, so the agent reads again
instead of taking an empty list for an empty one. There is deliberately no "rows stopped changing"
heuristic: it cannot tell a list still loading from one that is empty.

### Effect class and principal (skaile-ai/platform#9032)

- `effectClass: 'routine' | 'external' | 'privileged' | 'never'` on an action declares how sensitive it
  is; the platform runs it at least that strict (or stricter, by the project's floor). A class above
  `routine` needs `requiresApproval: true`. An action that breaks this rule, or names an unknown class,
  is not registered at all and an error is logged; the platform would refuse it anyway.
- Every handler gets `ctx.principal`, whose agent turn made the call: `{ kind: 'human', userId }`,
  `{ kind: 'trigger', source, userId }`, `{ kind: 'mixed', userIds }` or `{ kind: 'unknown' }`. For an
  approved action it is the turn that proposed it, not the approver. It is not used for authorization:
  the app's identity is still its own logged-in user. `audit(ctx, action, details)` writes one line with
  it to the session log; `form.submit` does so on every call.

## Wiring

The package holds one registry per page. In a tsm app whose views are separate bundles, serve it once
through the import map as a shared library, so that the shell and every bundle share that registry.
Integration step by step: [AGENTS.md](AGENTS.md).

Shell (owns the router):

```ts
startSkailify({
  appId: 'shop',                                      // the id in skaile.preview.json
  views: tsmViewSource(context, 'shop.view'),         // or your own () => ViewDescriptor[]
  router,
  resolveReference: (type, idOrName) => /* a loaded EObject */,
})
```

`tsmViewSource` reads the view services' `view`, `order`, `menu` and `objectTypes` properties and the
service's `title`. Each of these can be renamed through `TsmViewContract`.

Views:

```ts
useAgentForm({ uiModel, model: () => editing.value, submit: save, messages: () => [error.value] })
useAgentList({ rows: () => shown.value, select: (row) => open(row), loading: () => loading.value })
registerAction({ name: 'orders.approve', requiresApproval: true, kind: 'effect', input, handler })
```

## SDK version

Peer `@skaile/workspaces` ^7.18.1 (app protocol 5.8.1). Its app-sdk carries both
fields of skaile-ai/platform#9032 itself (skaile-ai/platform#9246): `effectClass` goes out on
`capability_register`, and every handler gets `ctx.principal`. A declaration the gateway would refuse
(an effect class above `routine` without `requiresApproval: true`) is not handed to the SDK, which throws
on it; the connection logs it and never registers it.

## Development

```sh
npm ci
npm test
npm run type-check
```

## License

Apache-2.0, see [LICENSE](../../LICENSE).
