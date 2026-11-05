/**
 * The two app-channel fields of skaile-ai/platform#9032: the `effectClass` an
 * app declares per capability on `capability_register`, and the turn
 * `principal` the gateway sends on every `capability_invoked`. Both are carried
 * by `@skaile/workspaces/app-sdk` since 6.13.0 (skaile-ai/platform#9246); this
 * module keeps the names the views use and the register guard.
 */
import type { AppHandlerContext, AppInvocationPrincipal } from '@skaile/workspaces/app-sdk'
import type { CapabilityEffectClass } from '@skaile/workspaces/types'

/** How sensitive an approval-gated action is; the platform runs it at least this strict. */
export type EffectClass = CapabilityEffectClass

export const EFFECT_CLASSES: readonly EffectClass[] = ['routine', 'external', 'privileged', 'never']

/**
 * Whose agent turn made a call. For an approved action it is the turn that
 * proposed it, not the person who approved it.
 */
export type TurnPrincipal = AppInvocationPrincipal

/** What a handler gets: the SDK's context, which says who asked (`principal`). */
export type SkailifyContext = AppHandlerContext

/**
 * Why the platform would refuse this declaration at registration, or null when
 * it is fine: absent, `routine`, or a stricter class on an action that asks for
 * approval. The same rule as the gateway's register guard.
 */
export function effectClassRefusal(capability: { name: string; requiresApproval?: boolean; effectClass?: unknown }): string | null {
  const declared = capability.effectClass
  if (declared === undefined || declared === 'routine') return null
  if (typeof declared !== 'string' || !(EFFECT_CLASSES as readonly string[]).includes(declared)) {
    return `${capability.name}: effectClass must be one of ${EFFECT_CLASSES.join(', ')}`
  }
  return capability.requiresApproval === true ? null : `${capability.name}: effectClass ${declared} requires requiresApproval: true`
}

/** One line for the session log: what the agent did, for whom. */
export function audit(ctx: SkailifyContext, action: string, details: Record<string, unknown> = {}): void {
  ctx.log.info(`skailify audit: ${action}`, { action, principal: ctx.principal, sessionId: ctx.sessionId, ...details })
}
