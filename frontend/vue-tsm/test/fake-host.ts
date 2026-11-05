/**
 * A fake Skaile host: the iframe handshake answered with a url and a token,
 * and a transport that plays the gateway — acknowledges app_hello, records
 * registrations and invokes capabilities the way the agent's calls arrive.
 */
import type { AppClientMessage, AppServerMessage, AppTransport } from '@skaile/workspaces/app-sdk'

export interface Registered {
  name: string
  kind?: string
  requiresApproval?: boolean
  effectClass?: string
  inputSchema: Record<string, unknown>
}

export class FakeGateway {
  readonly hellos: { appId: string }[] = []
  readonly tokens: string[] = []
  readonly capabilities = new Map<string, Registered>()
  /** Every capability name that went out in a register frame, in order */
  readonly sent: string[] = []
  private handlers = new Set<(message: AppServerMessage) => void>()
  private disconnects = new Set<() => void>()
  private pending = new Map<string, (result: unknown) => void>()
  private calls = 0
  private minted = 0
  connected = false

  /** The host side of the iframe handshake: a fresh single-use token every time. */
  resolveParams = async (appId: string, parentOrigin: string) => {
    if (parentOrigin !== 'https://skaile.test') throw new Error(`unexpected parent origin ${parentOrigin}`)
    return { appId, url: 'wss://skaile.test/app-connect', token: `token-${++this.minted}` }
  }

  createTransport = (_url: string, token: () => string | Promise<string>): AppTransport => {
    const gateway = this
    return {
      get connected() {
        return gateway.connected
      },
      async connect() {
        gateway.tokens.push(await token())
        gateway.connected = true
      },
      async disconnect() {
        gateway.connected = false
      },
      send: (message: AppClientMessage) => this.receive(message),
      onMessage: (handler) => {
        this.handlers.add(handler)
        return () => this.handlers.delete(handler)
      },
      onDisconnect: (handler) => {
        this.disconnects.add(handler)
        return () => this.disconnects.delete(handler)
      },
    }
  }

  /** Calls a capability as the agent would, from the turn of `principal` (null: a frame without one); resolves with what the app answered. */
  invoke(name: string, input: unknown = {}, principal: unknown = { kind: 'human', userId: 'user-1' }): Promise<unknown> {
    if (!this.capabilities.has(name)) throw new Error(`${name} is not registered; registered: ${[...this.capabilities.keys()].join(', ')}`)
    const callId = `call-${++this.calls}`
    return new Promise((resolve) => {
      this.pending.set(callId, resolve)
      this.emit({ type: 'capability_invoked', callId, name, input, invokedBy: 'agent', ...(principal === null ? {} : { principal }) } as AppServerMessage)
    })
  }

  /** Drops the socket, as a gateway restart does. */
  drop(): void {
    this.connected = false
    for (const handler of [...this.disconnects]) handler()
  }

  private receive(message: AppClientMessage): void {
    switch (message.type) {
      case 'app_hello':
        this.hellos.push({ appId: message.appId })
        queueMicrotask(() => this.emit({ type: 'app_hello_ack', sessionId: 'session-1', userId: 'user-1' }))
        return
      case 'capability_register':
        for (const capability of message.capabilities as unknown as Registered[]) {
          this.sent.push(capability.name)
          this.capabilities.set(capability.name, capability)
        }
        return
      case 'capability_deregister':
        for (const name of message.names) this.capabilities.delete(name)
        return
      case 'capability_result':
        this.pending.get(message.callId)?.(message.result)
        this.pending.delete(message.callId)
    }
  }

  private emit(message: AppServerMessage): void {
    for (const handler of [...this.handlers]) handler(message)
  }
}

/** A window embedded by Skaile: a parent, and the parent origin on the URL. */
export const embedded = { parent: {}, location: { search: '?skaileParentOrigin=https%3A%2F%2Fskaile.test' } }
export const standalone = { get parent() { return standalone }, location: { search: '' } }
