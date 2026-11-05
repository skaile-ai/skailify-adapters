/**
 * `@skaile/skailify-vue-tsm` — makes a Vue/tsm app agent-controllable inside a Skaile
 * session (the Skailify app channel). App-agnostic: the app passes in its view
 * registry, its router and its app id; views register their open forms, their
 * lists and actions of their own.
 *
 * The shell calls {@link startSkailify} once. Views use {@link useAgentForm},
 * {@link useAgentList} and {@link registerAction}. Standalone (not embedded in
 * Skaile) nothing connects and the registrations cost nothing.
 *
 * A shared library: served once by platform.vue through the import map, so the
 * shell and every view bundle share one registry.
 */
import { getCurrentScope, onScopeDispose } from 'vue'
import * as z from 'zod'
import type { Bridge, WaitOptions } from './bridge.js'
import { startSkailify as start, type SkailifyHandle, type SkailifyOptions } from './connect.js'
import { registry, type AgentAction, type FormRegistration, type ListRegistration } from './registry.js'

export { z }
export { createBridge, defineCapability, MAX_WAIT_MS, type Bridge, type WaitOptions, type BridgeOptions, type SkailifyCapability, type FormState, type ListRow, type ListState, type ViewState } from './bridge.js'
export { skaileParentOrigin, type ConnectionStatus, type SkailifyHandle, type SkailifyOptions } from './connect.js'
export { defaultLabel, idOf, missingRequired, readFields, setFields, widgetsOf, type FieldError, type FieldInfo, type Labeler, type ReferenceResolver, type SetFieldsResult, type ValueWriter } from './forms.js'
export { audit, effectClassRefusal, EFFECT_CLASSES, type EffectClass, type SkailifyContext, type TurnPrincipal } from './platform.js'
export { AgentRegistry, registry, type AgentAction, type FormRegistration, type ListRegistration, type SubmitOutcome } from './registry.js'
export { tsmViewSource, type TsmViewContract, type ViewDescriptor, type ViewSource } from './views.js'

let current: SkailifyHandle | null = null

/** Starts Skailify for the page (once; a second call replaces the first). Returns the handle; `close()` tears it down. */
export function startSkailify(options: SkailifyOptions): SkailifyHandle {
  void current?.close()
  current = start(options)
  return current
}

/** The running bridge, for actions that navigate; null before {@link startSkailify}. */
export function skailifyBridge(): Bridge | null {
  return current?.bridge ?? null
}

/**
 * Shows a view, optionally with an object selected, the way the generic `navigate` capability does.
 * Resolves false when the view did not settle in time.
 */
export async function navigateTo(view: string, objectId?: string, wait?: WaitOptions): Promise<boolean> {
  if (!current) throw new Error('skailify: not started')
  return current.bridge.navigate(view, objectId, wait)
}

function whileInScope(dispose: () => void): () => void {
  if (getCurrentScope()) onScopeDispose(dispose)
  return dispose
}

/** Registers an open form for as long as the calling component (or effect scope) lives. */
export function useAgentForm(form: FormRegistration): () => void {
  return whileInScope(registry.registerForm(form))
}

/** Registers a list for as long as the calling component (or effect scope) lives. */
export function useAgentList<T>(list: ListRegistration<T>): () => void {
  return whileInScope(registry.registerList(list))
}

/**
 * Adds a view-specific action. The name is relative (`orders.approve`); the agent sees it as
 * `app.<appId>.<name>`. Returns the function that removes it; inside a component it is removed
 * with the component.
 */
export function registerAction<T extends z.ZodTypeAny, R extends z.ZodTypeAny | undefined = undefined>(action: AgentAction<T, R>): () => void {
  return whileInScope(registry.registerAction(action))
}
