/**
 * The generic capabilities: what an agent can read and do in any Vue/tsm app,
 * derived from the view registry, the router and the registered forms and
 * lists. Pure of the SDK's transport — `connect.ts` puts them on the wire.
 */
import type { EObject } from '@emfts/core'
import { defineAppCapability, type DefineAppCapabilityArgs, type DefinedAppCapability } from '@skaile/workspaces/app-sdk'
import { shallowRef, watch } from 'vue'
import type { Router } from 'vue-router'
import * as z from 'zod'
import {
  defaultLabel,
  formName,
  idOf,
  missingRequired,
  readFields,
  setFields,
  typeNameOf,
  type FieldError,
  type Labeler,
  type ReferenceResolver,
  type ValueWriter,
} from './forms.js'
import { audit, effectClassRefusal } from './platform.js'
import { registry as sharedRegistry, type AgentAction, type AgentRegistry, type FormRegistration, type ListRegistration, type Registered } from './registry.js'
import type { ViewDescriptor, ViewSource } from './views.js'

export interface BridgeOptions {
  /** The app's id: the `id` of its entry in `skaile.preview.json` */
  appId: string
  /**
   * Put between `app.` and every capability name: `shop` gives `app.shop.list_views`.
   * The platform shows each name to the agent under the app id already (`app.<appId>.<name>`,
   * skaile-ai/platform#9031), so this stays empty unless a platform without that namespacing must
   * keep two apps apart.
   */
  capabilityPrefix?: string
  views: ViewSource
  router: Router
  /** The query parameter a view reads the selected object's id from; default `id` */
  selectionParam?: string
  /** The view id of a route; default the first path segment, the first menu view for `/` */
  viewOf?: (path: string, views: ViewDescriptor[]) => string | undefined
  /** How objects are called in lists and references; default `name`, `title`, … else the id */
  label?: Labeler
  /** Finds the object a reference field is set to, by id or label */
  resolveReference?: ReferenceResolver
  /** Writes a form value; default eSet plus the composer's expression tick */
  write?: ValueWriter
  /** Rows per list in `get_view_state`; default 50 */
  maxRows?: number
  /** How long a call waits for the view to settle before the state is read; default 3000 ms, at most {@link MAX_WAIT_MS} */
  settleTimeoutMs?: number
  registry?: AgentRegistry
}

/** A capability as the SDK defines it; the SDK carries `effectClass` and `ctx.principal` itself. */
export type SkailifyCapability = DefinedAppCapability

/**
 * Defines a capability. A declaration the gateway would refuse is not handed to the SDK, which throws on
 * it; it keeps its `effectClass` so the connection logs it and never registers it.
 */
export function defineCapability<T extends z.ZodTypeAny, R extends z.ZodTypeAny | undefined = undefined>(args: AgentAction<T, R>): SkailifyCapability {
  if (!effectClassRefusal(args)) return defineAppCapability(args as DefineAppCapabilityArgs<T, R>)
  const { effectClass, ...rest } = args
  return { ...defineAppCapability(rest as DefineAppCapabilityArgs<T, R>), effectClass } as SkailifyCapability
}

/** The longest a call may wait for the view to settle */
export const MAX_WAIT_MS = 10_000
/** How long the registry must stay quiet after a navigation before the view counts as registered */
const QUIET_MS = 150

/** How long a call waits before it reads the state. */
export interface WaitOptions {
  /**
   * `none`: read at once. `registered`: until the view has registered its lists and forms. `loaded` (default): and none
   * of them says it is still loading; a view without a loading signal is waited for as `registered`.
   */
  wait?: 'none' | 'registered' | 'loaded'
  /** In addition, until this list is shown with at least `minRows` rows (any list when unnamed) and this form is open */
  waitFor?: { list?: string; minRows?: number; form?: string }
  /** How long to wait at most; default the bridge's settle timeout, capped at {@link MAX_WAIT_MS} */
  timeoutMs?: number
}

export interface Bridge {
  /**
   * The generic capabilities plus every registered action, with their full names. The same
   * registration gives the same object, so a changed one can be told by identity.
   */
  capabilities(): SkailifyCapability[]
  /** The full name of a relative one: `form.submit` → `app.<prefix>.form.submit` */
  fullName(name: string): string
  /** Shows a view, optionally with an object selected, and waits until it has settled; false when the wait timed out */
  navigate(view: string, objectId?: string, wait?: WaitOptions): Promise<boolean>
  viewState(): ViewState
}

export interface ListRow {
  id: string
  label: string
}

export interface ListState {
  id: string
  type: string
  /** Rows shown after search and filter */
  shown: number
  total: number
  rows: ListRow[]
  /** true when `rows` was cut at the row limit */
  truncated: boolean
  /** true while the view is still loading the rows: they are not complete yet, read again */
  loading: boolean
}

export interface FormState {
  id: string
  title?: string
  type: string
  /** The object's id; empty for a new object not saved yet */
  objectId: string
  fields: ReturnType<typeof readFields>
  /** Required fields that are empty */
  missing: FieldError[]
  /** What the view shows for this form right now (errors, notices) */
  messages: string[]
  canSubmit: boolean
}

export interface ViewState {
  view: { id: string; title: string } | null
  path: string
  query: Record<string, string>
  selected: { type: string; id: string; label: string } | null
  lists: ListState[]
  forms: FormState[]
  /** The view-specific actions available now, by full name */
  actions: string[]
  /** false when the wait for the view timed out, or a list or form is still loading: the state may be incomplete, read again */
  settled: boolean
}

const PATH_SEGMENT = /^\/*([^/?#]*)/

export function createBridge(options: BridgeOptions): Bridge {
  const registry = options.registry ?? sharedRegistry
  const label = options.label ?? defaultLabel
  const selectionParam = options.selectionParam ?? 'id'
  const maxRows = options.maxRows ?? 50
  const settleTimeout = Math.min(options.settleTimeoutMs ?? 3000, MAX_WAIT_MS)
  const prefix = options.capabilityPrefix ? `${options.capabilityPrefix}.` : ''
  const fullName = (name: string) => `app.${prefix}${name}`

  const viewOf = options.viewOf ?? ((path: string, views: ViewDescriptor[]) => {
    const segment = PATH_SEGMENT.exec(path)?.[1] ?? ''
    return segment === '' ? views.find((v) => v.menu)?.id : segment
  })

  function currentView(): ViewDescriptor | undefined {
    const views = options.views()
    const id = viewOf(options.router.currentRoute.value.path, views)
    return views.find((v) => v.id === id)
  }

  function query(): Record<string, string> {
    return Object.fromEntries(
      Object.entries(options.router.currentRoute.value.query)
        .filter(([, v]) => typeof v === 'string')
        .map(([k, v]) => [k, v as string]),
    )
  }

  // ── Lists ─────────────────────────────────────────────────────────────────
  const isEObject = (row: unknown): row is EObject => !!row && typeof (row as EObject).eClass === 'function'
  const rowId = (list: ListRegistration<unknown>, row: unknown) =>
    list.idOf ? list.idOf(row) : isEObject(row) ? idOf(row) : String((row as { id?: unknown })?.id ?? '')
  const rowLabel = (list: ListRegistration<unknown>, row: unknown) =>
    list.labelOf ? list.labelOf(row) : isEObject(row) ? label(row) : String(row ?? '')
  const rowType = (list: ListRegistration<unknown>, row: unknown) =>
    list.typeOf ? list.typeOf(row) : isEObject(row) ? typeNameOf(row) : ''

  function listIds(lists: Registered<ListRegistration<unknown>>[]): string[] {
    const seen = new Map<string, number>()
    return lists.map(({ value }) => {
      const base = value.id ?? 'list'
      const n = (seen.get(base) ?? 0) + 1
      seen.set(base, n)
      return n === 1 ? base : `${base}-${n}`
    })
  }

  function listStates(): ListState[] {
    const lists = registry.openLists()
    const ids = listIds(lists)
    return lists.map(({ value: list }, index) => {
      const rows = list.rows()
      return {
        id: ids[index],
        type: rows.length ? rowType(list, rows[0]) : '',
        shown: rows.length,
        total: list.total ? list.total() : rows.length,
        rows: rows.slice(0, maxRows).map((row) => ({ id: rowId(list, row), label: rowLabel(list, row) })),
        truncated: rows.length > maxRows,
        loading: list.loading?.() ?? false,
      }
    })
  }

  // ── Forms ─────────────────────────────────────────────────────────────────
  function formIds(forms: Registered<FormRegistration>[]): string[] {
    const seen = new Map<string, number>()
    return forms.map(({ value }) => {
      const base = value.id ?? formName(value.uiModel)
      const n = (seen.get(base) ?? 0) + 1
      seen.set(base, n)
      return n === 1 ? base : `${base}-${n}`
    })
  }

  function openForms(): { id: string; form: FormRegistration; model: EObject }[] {
    const forms = registry.openForms()
    const ids = formIds(forms)
    return forms.flatMap(({ value }, index) => {
      const model = value.model()
      return model ? [{ id: ids[index], form: value, model }] : []
    })
  }

  /** The form a call means: the named one, else the only one open. */
  function pickForm(id: string | undefined) {
    const forms = openForms()
    if (id) {
      const found = forms.find((f) => f.id === id)
      if (!found) throw new Error(`no open form ${id}; open forms: ${forms.map((f) => f.id).join(', ') || 'none'}`)
      return found
    }
    if (forms.length === 1) return forms[0]
    if (forms.length === 0) throw new Error('no form is open; open an object first (navigate, open or select)')
    throw new Error(`several forms are open, name one: ${forms.map((f) => f.id).join(', ')}`)
  }

  function viewState(): ViewState {
    const view = currentView()
    const forms: FormState[] = openForms().map(({ id, form, model }) => ({
      id,
      title: form.title,
      type: typeNameOf(model),
      objectId: idOf(model),
      fields: readFields(form.uiModel, model, label),
      missing: missingRequired(form.uiModel, model),
      messages: form.messages?.().filter(Boolean) ?? [],
      canSubmit: !!form.submit,
    }))
    const q = query()
    const formObject = openForms()[0]?.model
    const selected = formObject && idOf(formObject)
      ? { type: typeNameOf(formObject), id: idOf(formObject), label: label(formObject) }
      : q[selectionParam]
        ? { type: '', id: q[selectionParam], label: '' }
        : null
    return {
      view: view ? { id: view.id, title: view.title } : null,
      path: options.router.currentRoute.value.path,
      query: q,
      selected,
      lists: listStates(),
      forms,
      actions: registry.allActions().map((a) => fullName(a.name)),
      settled: !registry.loading(),
    }
  }

  /** Whether the list and form a call waits for are there */
  function waitedFor(waitFor: WaitOptions['waitFor']): boolean {
    if (!waitFor) return true
    if (waitFor.form && !openForms().some((f) => f.id === waitFor.form)) return false
    if (waitFor.list !== undefined || waitFor.minRows !== undefined) {
      const lists = registry.openLists()
      const ids = listIds(lists)
      const candidates = waitFor.list ? lists.filter((_, i) => ids[i] === waitFor.list) : lists
      if (!candidates.some(({ value }) => value.rows().length >= (waitFor.minRows ?? 0))) return false
    }
    return true
  }

  /**
   * Waits until the view has settled: after a navigation the registry has been quiet for {@link QUIET_MS} (`quietMs`),
   * then — with `loaded` — no visible list or form says it is loading, and what `waitFor` names is there. The loading
   * getters are watched reactively. Resolves false when the timeout comes first: the state is read as it is then.
   */
  function settle(wait: WaitOptions = {}, quietMs = QUIET_MS): Promise<boolean> {
    const mode = wait.wait ?? 'loaded'
    if (mode === 'none' && !wait.waitFor) return Promise.resolve(true)
    const quietFor = mode === 'none' ? 0 : quietMs
    const timeout = Math.min(Math.max(wait.timeoutMs ?? settleTimeout, 0), MAX_WAIT_MS)
    const ready = () => (mode !== 'loaded' || !registry.loading()) && waitedFor(wait.waitFor)
    return new Promise((resolve) => {
      let quiet = quietFor === 0
      let quietTimer: ReturnType<typeof setTimeout> | undefined
      let finished = false
      // the registry is no reactive source: a change re-runs the watch so it tracks the getters of new registrations
      const registrations = shallowRef(0)
      const finish = (settled: boolean) => {
        if (finished) return
        finished = true
        clearTimeout(quietTimer)
        clearTimeout(limit)
        unsubscribe()
        stop()
        resolve(settled)
      }
      const check = () => {
        if (quiet && ready()) finish(true)
      }
      const limit = setTimeout(() => finish(quiet && ready()), timeout)
      const stop = watch(() => {
        void registrations.value
        return ready()
      }, (now) => now && check(), { flush: 'sync' })
      const unsubscribe = registry.onChange(() => {
        registrations.value++
        if (quietFor === 0) return check()
        quiet = false
        clearTimeout(quietTimer)
        quietTimer = setTimeout(() => {
          quiet = true
          check()
        }, quietFor)
      })
      if (quiet) {
        check()
      } else {
        quietTimer = setTimeout(() => {
          quiet = true
          check()
        }, quietFor)
      }
    })
  }

  async function navigate(viewId: string, objectId?: string, wait?: WaitOptions): Promise<boolean> {
    const views = options.views()
    if (!views.some((v) => v.id === viewId)) {
      throw new Error(`no view ${viewId}; views: ${views.map((v) => v.id).join(', ')}`)
    }
    const failure = await options.router.push({ path: `/${viewId}`, query: objectId ? { [selectionParam]: objectId } : {} })
    // vue-router answers a refused navigation instead of throwing; a duplicated one is no failure
    if (failure && !String(failure.message).includes('redundant')) throw new Error(`navigation refused: ${failure.message}`)
    return settle(wait)
  }

  // ── The capabilities ──────────────────────────────────────────────────────
  const waitInput = {
    wait: z.enum(['none', 'registered', 'loaded']).optional().describe(
      'How long to wait before reading the state: "loaded" (default) until the lists and forms are registered and none is still '
      + 'loading; "registered" until they are registered; "none" not at all',
    ),
    waitFor: z.object({
      list: z.string().optional().describe('List id to wait for'),
      minRows: z.number().int().min(0).optional().describe('Rows that list (any list when unnamed) must show at least'),
      form: z.string().optional().describe('Form id to wait for'),
    }).optional().describe('Wait in addition until this list or form is shown, for views that do not report their loading'),
    timeoutMs: z.number().int().min(0).optional().describe(`How long to wait at most, in ms; default ${settleTimeout}, at most ${MAX_WAIT_MS}`),
  }
  const settledNote =
    'Lists still loading have loading: true. When the wait timed out or something is still loading, settled is false: '
    + 'the state may be incomplete (an empty list may only not have loaded yet), so read it again with get_view_state.'
  const waitOf = ({ wait, waitFor, timeoutMs }: WaitOptions): WaitOptions => ({ wait, waitFor, timeoutMs })

  const viewOut = z.object({ id: z.string(), title: z.string(), menu: z.boolean(), objectTypes: z.array(z.string()) })

  const generic: SkailifyCapability[] = [
    defineCapability({
      name: fullName('list_views'),
      displayName: 'List the views',
      description:
        `List the views (pages) of the ${options.appId} app: id, title, whether it is in the menu, and which object types it opens. `
        + `Use the id with ${fullName('navigate')}.`,
      kind: 'query',
      input: z.object({}),
      output: z.object({ current: z.string().nullable(), views: z.array(viewOut) }),
      handler: async () => ({ current: currentView()?.id ?? null, views: options.views() }),
    }),
    defineCapability({
      name: fullName('get_view_state'),
      displayName: 'Read what the user sees',
      description:
        'Read the current state of the app UI: the view shown, the selected object (type, id), the visible list rows '
        + `(id and label, at most ${maxRows} per list), every open form with its fields, current values, required fields that `
        + 'are empty and the messages the view shows, and the view-specific actions available now. Call this first, and again after acting. '
        + `By default it first waits until no list or form is loading. ${settledNote}`,
      kind: 'query',
      input: z.object({ ...waitInput }),
      handler: async (input) => {
        const settled = await settle(waitOf(input), 0)
        return { ...viewState(), settled: settled && !registry.loading() }
      },
    }),
    defineCapability({
      name: fullName('navigate'),
      displayName: 'Show a view',
      description:
        `Show a view of the app, optionally with an object selected by id (see ${fullName('list_views')}). `
        + `Waits until the view has loaded (see wait), then returns the view state. ${settledNote}`,
      kind: 'effect',
      input: z.object({
        view: z.string().describe('View id from list_views'),
        objectId: z.string().optional().describe('Id of the object to select in that view'),
        ...waitInput,
      }),
      handler: async ({ view, objectId, ...wait }) => {
        const settled = await navigate(view, objectId, waitOf(wait))
        return { ...viewState(), settled: settled && !registry.loading() }
      },
    }),
    defineCapability({
      name: fullName('open'),
      displayName: 'Open an object',
      description:
        'Open an object by type (EClass name, e.g. from a list in get_view_state) and id, in the view that shows that type. '
        + `Returns the view state afterwards. ${settledNote}`,
      kind: 'effect',
      input: z.object({
        type: z.string().describe('Object type, e.g. "Order"'),
        id: z.string().describe('Object id'),
        ...waitInput,
      }),
      handler: async ({ type, id, ...wait }) => {
        const views = options.views()
        const view = views.find((v) => v.objectTypes.includes(type))
        if (!view) {
          const known = [...new Set(views.flatMap((v) => v.objectTypes))]
          throw new Error(`no view opens ${type}; types with a view: ${known.join(', ') || 'none'}`)
        }
        const settled = await navigate(view.id, id, waitOf(wait))
        return { ...viewState(), settled: settled && !registry.loading() }
      },
    }),
    defineCapability({
      name: fullName('select'),
      displayName: 'Select a list row',
      description:
        'Select a row of a list in the current view by its id, as a click on the row does (usually opens its form). '
        + `Name the list when the view shows several. Returns the view state afterwards. ${settledNote}`,
      kind: 'effect',
      input: z.object({
        id: z.string().describe('Row id from get_view_state'),
        list: z.string().optional().describe('List id from get_view_state, when there are several'),
        ...waitInput,
      }),
      handler: async ({ id, list, ...wait }) => {
        const lists = registry.openLists()
        const ids = listIds(lists)
        const candidates = list ? lists.filter((_, i) => ids[i] === list) : lists
        if (candidates.length === 0) throw new Error(list ? `no list ${list}; lists: ${ids.join(', ') || 'none'}` : 'the current view shows no list')
        for (const { value } of candidates) {
          const row = value.rows().find((r) => rowId(value, r) === id)
          if (!row) continue
          if (value.select) {
            await value.select(row)
          } else {
            await options.router.push({ query: { ...options.router.currentRoute.value.query, [selectionParam]: id } })
          }
          const settled = await settle(waitOf(wait))
          return { ...viewState(), settled: settled && !registry.loading() }
        }
        throw new Error(`no row ${id} is shown${list ? ` in ${list}` : ''}; a search or filter may hide it`)
      },
    }),
    defineCapability({
      name: fullName('form.set_fields'),
      displayName: 'Fill in form fields',
      description:
        'Set fields of an open form, as typing into it does; nothing is saved until form.submit. Keys are field names from '
        + 'get_view_state. Values: text and dates (yyyy-mm-dd) as strings, amounts as decimal strings with a point ("1190.00"), '
        + 'booleans, enum literal names, references by the target object\'s id. Valid fields are written; each refused field comes '
        + 'back in errors with the reason.',
      kind: 'effect',
      input: z.object({
        form: z.string().optional().describe('Form id from get_view_state; may be left out when one form is open'),
        fields: z.record(z.string(), z.unknown()).describe('Field name → value'),
      }),
      output: z.object({
        form: z.string(),
        applied: z.array(z.string()),
        errors: z.array(z.object({ field: z.string(), message: z.string() })),
        missing: z.array(z.object({ field: z.string(), message: z.string() })),
      }),
      handler: async ({ form, fields }) => {
        const target = pickForm(form)
        const result = setFields(target.form.uiModel, target.model, fields, { resolve: options.resolveReference, write: options.write })
        return { form: target.id, ...result, missing: missingRequired(target.form.uiModel, target.model) }
      },
    }),
    defineCapability({
      name: fullName('form.submit'),
      displayName: 'Save a form',
      description:
        'Save an open form, as its save button does. Refused with structured errors when required fields are empty or the '
        + 'app rejects the values (e.g. a model constraint); then fix them with form.set_fields and submit again.',
      kind: 'effect',
      input: z.object({
        form: z.string().optional().describe('Form id from get_view_state; may be left out when one form is open'),
      }),
      output: z.object({
        form: z.string(),
        ok: z.boolean(),
        objectId: z.string(),
        errors: z.array(z.object({ field: z.string().optional(), message: z.string() })),
      }),
      handler: async ({ form }, ctx) => {
        const target = pickForm(form)
        if (!target.form.submit) throw new Error(`the form ${target.id} cannot be saved from here`)
        audit(ctx, fullName('form.submit'), { form: target.id, type: typeNameOf(target.model), objectId: idOf(target.model) })
        const missing = missingRequired(target.form.uiModel, target.model)
        if (missing.length > 0) return { form: target.id, ok: false, objectId: idOf(target.model), errors: missing }
        let outcome: Awaited<ReturnType<NonNullable<FormRegistration['submit']>>>
        try {
          outcome = await target.form.submit()
        } catch (error) {
          return { form: target.id, ok: false, objectId: idOf(target.model), errors: [{ message: (error as Error).message }] }
        }
        const ok = outcome === undefined || outcome === true || (typeof outcome === 'object' && outcome.ok)
        const message = typeof outcome === 'object' ? outcome.message : undefined
        const shown = target.form.messages?.().filter(Boolean) ?? []
        await settle()
        // the form may have been replaced by the saved object: read the id from what is open now
        const now = openForms().find((f) => f.form === target.form)?.model ?? target.model
        return {
          form: target.id,
          ok,
          objectId: idOf(now),
          errors: ok ? [] : [{ message: message || shown.join(' ') || 'the app refused to save' }],
        }
      },
    }),
  ]

  const defined = new WeakMap<AgentAction, SkailifyCapability>()
  const own = (action: AgentAction): SkailifyCapability => {
    let capability = defined.get(action)
    if (!capability) {
      capability = defineCapability({ ...action, name: fullName(action.name) })
      defined.set(action, capability)
    }
    return capability
  }

  return {
    capabilities: () => [...generic, ...registry.allActions().map(own)],
    fullName,
    navigate,
    viewState,
  }
}
