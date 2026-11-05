/**
 * The wiring to Skaile: handshake with the embedding host, the app channel
 * with a token provider for reconnects, and the capabilities kept in step with
 * the registry. The only file that talks to `@skaile/workspaces/app-sdk`'s
 * transport.
 *
 * It connects only inside a Skaile session: the platform embeds a `protocol`
 * preview app in an iframe whose URL carries `skaileParentOrigin`. Opened on
 * its own, the app has neither, and nothing here runs.
 */
import {
  consoleLogger,
  createSkaileApp,
  createWebSocketAppTransport,
  PARENT_ORIGIN_QUERY_PARAM,
  resolveAppConnectionParams,
  type AppConnectionParams,
  type AppLogger,
  type AppSession,
  type AppTransport,
  type SessionTokenProvider,
  type SkaileApp,
} from '@skaile/workspaces/app-sdk'
import { createBridge, type Bridge, type BridgeOptions, type SkailifyCapability } from './bridge.js'
import { effectClassRefusal } from './platform.js'
import { registry as sharedRegistry } from './registry.js'

export type ConnectionStatus =
  | { state: 'standalone' }
  | { state: 'connecting' }
  | { state: 'connected'; sessionId: string; userId?: string }
  | { state: 'reconnecting' }
  | { state: 'error'; message: string }

interface WindowLike {
  readonly parent: unknown
  readonly location: { readonly search: string }
}

export interface SkailifyOptions extends BridgeOptions {
  /** `auto` (default): connect when embedded in Skaile; `false`: never */
  enabled?: 'auto' | false
  logger?: AppLogger
  onStatus?: (status: ConnectionStatus) => void
  /** For tests: the window, the host handshake and the transport */
  windowRef?: WindowLike
  resolveParams?: (appId: string, parentOrigin: string) => Promise<AppConnectionParams>
  createTransport?: (url: string, token: SessionTokenProvider) => AppTransport
}

export interface SkailifyHandle {
  bridge: Bridge
  /** The SDK peer; null when not embedded */
  app: SkaileApp | null
  /** Resolves when the first connect is done (or failed, or there is nothing to connect) */
  ready: Promise<void>
  close(): Promise<void>
}

/**
 * The embedding host's origin, or null when the app is not embedded in Skaile.
 * Read once at start: the app's own navigation drops the query parameter, and
 * every reconnect needs it.
 */
export function skaileParentOrigin(windowRef: WindowLike | undefined = globalThis as unknown as WindowLike): string | null {
  if (!windowRef?.location || !windowRef.parent || windowRef.parent === windowRef) return null
  const origin = new URLSearchParams(windowRef.location.search).get(PARENT_ORIGIN_QUERY_PARAM)
  return origin && origin !== '*' && origin !== 'null' ? origin : null
}

export function startSkailify(options: SkailifyOptions): SkailifyHandle {
  const bridge = createBridge(options)
  const status = options.onStatus ?? (() => {})
  const parentOrigin = options.enabled === false ? null : skaileParentOrigin(options.windowRef)

  if (!parentOrigin) {
    status({ state: 'standalone' })
    return { bridge, app: null, ready: Promise.resolve(), close: async () => {} }
  }

  const logger = options.logger ?? consoleLogger
  const resolveParams = options.resolveParams
    ?? ((appId: string, origin: string) => resolveAppConnectionParams({ appId, parentOrigin: origin }))
  const createTransport = options.createTransport
    ?? ((url: string, sessionToken: SessionTokenProvider) => createWebSocketAppTransport({ url, sessionToken }))

  let closed = false
  let app: SkaileApp | null = null
  const handle: SkailifyHandle = { bridge, app: null, ready: Promise.resolve(), close: async () => {} }

  /**
   * Brings the registered capabilities in step with the bridge's: new and changed ones register, gone ones
   * deregister. A declaration the platform would refuse (an effect class above `routine` without approval)
   * is never sent; it is logged instead.
   */
  let registered = new Map<string, SkailifyCapability>()
  const refused = new WeakSet<SkailifyCapability>()
  function sync() {
    if (!app) return
    const now = new Map<string, SkailifyCapability>()
    for (const capability of bridge.capabilities()) {
      const refusal = effectClassRefusal(capability)
      if (!refusal) {
        now.set(capability.name, capability)
      } else if (!refused.has(capability)) {
        refused.add(capability)
        logger.error(`skailify: not registered, ${refusal}`, { name: capability.name })
      }
    }
    const before = registered
    registered = now
    for (const name of before.keys()) if (!now.has(name)) app.deregisterCapability(name)
    for (const [name, capability] of now) if (before.get(name) !== capability) app.registerCapability(capability)
  }
  const unsubscribe = (options.registry ?? sharedRegistry).onActionsChange(sync)

  async function run(): Promise<void> {
    status({ state: 'connecting' })
    // The first token comes with the url; every reconnect asks the host for a fresh one — the gateway burns it at app_hello.
    let primed: AppConnectionParams | null = await resolveParams(options.appId, parentOrigin!)
    if (closed) return
    const transport = createTransport(primed.url, () => {
      if (primed) {
        const token = primed.token
        primed = null
        return token
      }
      return resolveParams(options.appId, parentOrigin!).then((params) => params.token)
    })
    app = createSkaileApp({ appId: options.appId, transport, logger })
    handle.app = app
    sync()
    app.onSessionChange((session: AppSession | null) => {
      status(session ? { state: 'connected', sessionId: session.sessionId, userId: session.userId } : { state: 'reconnecting' })
    })
    if (closed) {
      await app.close()
      return
    }
    await app.connect()
  }

  handle.ready = run().catch((error: unknown) => {
    if (closed) return
    const message = error instanceof Error ? error.message : String(error)
    logger.error('skailify: could not connect to Skaile', { message })
    status({ state: 'error', message })
  })
  handle.close = async () => {
    closed = true
    unsubscribe()
    await app?.close()
  }
  return handle
}
