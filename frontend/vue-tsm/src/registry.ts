/**
 * What the views tell the agent about themselves while they are on screen: the
 * forms they show, the lists they show, and actions of their own.
 *
 * One registry per page. The package is a shared library (one instance through
 * the import map), so a view bundle and the shell see the same registry. It
 * works the same embedded or standalone; standalone nobody reads it.
 */
import type { EObject } from '@emfts/core'
import type { DefineAppCapabilityArgs } from '@skaile/workspaces/app-sdk'
import type * as z from 'zod'
import type { EffectClass, SkailifyContext } from './platform.js'

/** How a submit went: `void`/`true` is success, `false` or `{ ok: false }` a refusal. */
export type SubmitOutcome = void | boolean | { ok: boolean; message?: string }

/** An open form: a UIModel shown for one EObject. */
export interface FormRegistration {
  /** Names the form for the agent; default the UIModel's `name` */
  id?: string
  /** What the form is called on screen, for the agent's orientation */
  title?: string
  /** The UIModel (an `uimodel:UIModel` EObject) the composer renders */
  uiModel: EObject
  /** The object the form edits right now; null while none is open */
  model: () => EObject | null | undefined
  /** Saves the form the way the view's own save button does. Without it the form is read and filled only. */
  submit?: () => Promise<SubmitOutcome> | SubmitOutcome
  /** The messages the view shows for this form right now (errors, notices) */
  messages?: () => string[]
  /**
   * Whether the view is still loading the form's object. Read reactively: an agent's navigation waits
   * until it is false. Without it the form counts as loaded as soon as it is registered.
   */
  loading?: () => boolean
}

/** A list of rows on screen. */
export interface ListRegistration<T = unknown> {
  /** Names the list; default `list`, numbered when a view shows several */
  id?: string
  /** The rows as shown: filtered and sorted */
  rows: () => readonly T[]
  /** All rows before filtering, for the total; default `rows` */
  total?: () => number
  /** Row id; default the EObject's ID attribute */
  idOf?: (row: T) => string
  /** Row label; default the package's label function */
  labelOf?: (row: T) => string
  /** The object type of the rows; default the EObject's EClass name */
  typeOf?: (row: T) => string
  /** What selecting a row does in this view; default navigation to `?<selectionParam>=<id>` */
  select?: (row: T) => void | Promise<void>
  /** Whether the list is on screen; default always (a view may keep a list mounted behind a detail) */
  visible?: () => boolean
  /**
   * Whether the view is still loading the rows. Read reactively: an agent's navigation waits until it
   * is false, and the list state reports it. Without it the rows count as loaded as soon as the list is
   * registered — a list filled asynchronously should pass it, or an agent may read it empty.
   */
  loading?: () => boolean
}

/**
 * A view-specific action. `name` is relative: the package adds `app.` and the app's capability prefix.
 * The handler's context carries the turn principal (`ctx.principal`).
 */
export type AgentAction<T extends z.ZodTypeAny = z.ZodTypeAny, R extends z.ZodTypeAny | undefined = z.ZodTypeAny | undefined> =
  Omit<DefineAppCapabilityArgs<T, R>, 'handler'> & {
    /**
     * How sensitive the action is (skaile-ai/platform#9032); default `routine`. A class above `routine`
     * needs `requiresApproval: true`; without it the action is not registered at all.
     */
    effectClass?: EffectClass
    handler: (args: z.infer<T>, ctx: SkailifyContext) => Promise<R extends z.ZodTypeAny ? z.infer<R> : unknown>
  }

export interface Registered<T> {
  key: number
  value: T
}

type Listener = () => void

export class AgentRegistry {
  private next = 1
  private readonly forms = new Map<number, FormRegistration>()
  private readonly lists = new Map<number, ListRegistration<unknown>>()
  private readonly actions = new Map<string, AgentAction>()
  private readonly listeners = new Set<Listener>()
  private readonly actionListeners = new Set<Listener>()

  registerForm(form: FormRegistration): () => void {
    return this.add(this.forms, form)
  }

  registerList<T>(list: ListRegistration<T>): () => void {
    return this.add(this.lists, list as unknown as ListRegistration<unknown>)
  }

  /** Adds (or replaces, by name) an action. The returned function removes exactly this registration. */
  registerAction<T extends z.ZodTypeAny, R extends z.ZodTypeAny | undefined = undefined>(added: AgentAction<T, R>): () => void {
    const action = added as unknown as AgentAction
    this.actions.set(action.name, action)
    this.emit(this.actionListeners)
    this.emit(this.listeners)
    return () => {
      if (this.actions.get(action.name) !== action) return
      this.actions.delete(action.name)
      this.emit(this.actionListeners)
      this.emit(this.listeners)
    }
  }

  openForms(): Registered<FormRegistration>[] {
    return [...this.forms].filter(([, form]) => form.model() != null).map(([key, value]) => ({ key, value }))
  }

  openLists(): Registered<ListRegistration<unknown>>[] {
    return [...this.lists].filter(([, list]) => list.visible?.() ?? true).map(([key, value]) => ({ key, value }))
  }

  /** Whether a visible list or a registered form says it is still loading */
  loading(): boolean {
    return this.openLists().some(({ value }) => value.loading?.() ?? false)
      || [...this.forms.values()].some((form) => form.loading?.() ?? false)
  }

  allActions(): AgentAction[] {
    return [...this.actions.values()]
  }

  /** Any change: forms, lists, actions. Used to wait until a navigation has settled. */
  onChange(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Actions added or removed — the capability set the agent sees changes. */
  onActionsChange(listener: Listener): () => void {
    this.actionListeners.add(listener)
    return () => this.actionListeners.delete(listener)
  }

  private add<T>(map: Map<number, T>, value: T): () => void {
    const key = this.next++
    map.set(key, value)
    this.emit(this.listeners)
    return () => {
      if (map.delete(key)) this.emit(this.listeners)
    }
  }

  private emit(listeners: Set<Listener>): void {
    for (const listener of [...listeners]) listener()
  }
}

/** The page's registry: one per page, shared through the import map. */
export const registry = new AgentRegistry()
