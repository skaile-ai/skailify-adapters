/**
 * Waiting for a view: lists whose rows arrive after the navigation, the loading
 * signal views report, the call options for views without one, and the honest
 * `settled: false` when the wait times out. Views are mounted the way the router
 * view mounts components: each in an effect scope that ends when the route leaves it.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { effectScope, shallowRef, type EffectScope } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { EObject } from '@emfts/core'
import { noopLogger } from '@skaile/workspaces/app-sdk'
import { registerAction, registry, useAgentForm, useAgentList, z, type SkailifyHandle, type ViewDescriptor } from '../src/index.js'
import { startSkailify } from '../src/connect.js'
import { embedded, FakeGateway } from './fake-host.js'
import { create, orderForm } from './fixture.js'

const VIEWS: ViewDescriptor[] = [
  { id: 'orders', title: 'Orders', menu: true, objectTypes: ['Order'] },
  { id: 'customers', title: 'Customers', menu: true, objectTypes: ['Customer'] },
  { id: 'plain', title: 'Plain', menu: true, objectTypes: [] },
]

const customers = [create('Customer', { id: 'c1', name: 'ACME' }), create('Customer', { id: 'c2', name: 'Globex' })]

/** How long the customers view takes to fetch its rows; null: it never finishes */
let fetchMs: number | null
/** Whether the customers view reports its loading */
let reportsLoading: boolean

/** The views' setups, as their components would do it */
const setups: Record<string, () => void> = {
  orders() {
    registerAction({ name: 'orders.approve', description: 'Approve an order', kind: 'effect', input: z.object({}), handler: async () => ({}) })
    const order = shallowRef<EObject | null>(null)
    useAgentForm({ uiModel: orderForm(), model: () => order.value, loading: () => !order.value })
    setTimeout(() => (order.value = create('Order', { id: 'o1' })), 300)
  },
  customers() {
    const rows = shallowRef<EObject[]>([])
    const loading = shallowRef(true)
    useAgentList({ id: 'customers', rows: () => rows.value, ...(reportsLoading ? { loading: () => loading.value } : {}) })
    if (fetchMs !== null) {
      setTimeout(() => {
        rows.value = customers
        loading.value = false
      }, fetchMs)
    }
  },
  plain() {},
}

let router: Router
let gateway: FakeGateway
let handle: SkailifyHandle
let mounted: EffectScope | undefined

beforeEach(async () => {
  fetchMs = 400
  reportsLoading = true
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:path(.*)*', component: { render: () => null } }] })
  // the router view: the old view's scope ends, the new view's setup runs in a scope of its own, a tick later
  router.afterEach((to, from) => {
    if (to.path === from.path && mounted) return
    mounted?.stop()
    mounted = undefined
    const setup = setups[to.path.slice(1)]
    if (!setup) return
    queueMicrotask(() => {
      mounted = effectScope()
      mounted.run(setup)
    })
  })
  await router.push('/plain')
  gateway = new FakeGateway()
  handle = startSkailify({
    appId: 'shop',
    views: () => VIEWS,
    router,
    settleTimeoutMs: 1000,
    logger: noopLogger,
    windowRef: embedded,
    resolveParams: gateway.resolveParams,
    createTransport: gateway.createTransport,
  })
  await handle.ready
})

afterEach(async () => {
  mounted?.stop()
  mounted = undefined
  await handle?.close()
})

type State = { settled: boolean; lists: Array<{ id: string; shown: number; loading: boolean; type: string }>; forms: Array<{ id: string }>; actions: string[] }

describe('a list whose rows arrive late', () => {
  it('navigate waits until the view says it has loaded', async () => {
    const state = await gateway.invoke('app.navigate', { view: 'customers' }) as State
    expect(state.settled).toBe(true)
    expect(state.lists).toEqual([expect.objectContaining({ id: 'customers', type: 'Customer', shown: 2, loading: false })])
  })

  it('get_view_state waits for it too', async () => {
    await gateway.invoke('app.navigate', { view: 'customers', wait: 'registered' })
    const state = await gateway.invoke('app.get_view_state') as State
    expect(state).toMatchObject({ settled: true, lists: [{ shown: 2, loading: false }] })
  })

  it('reports loading and settled: false when it times out, instead of an empty list as the truth', async () => {
    fetchMs = null
    const started = Date.now()
    const state = await gateway.invoke('app.navigate', { view: 'customers', timeoutMs: 400 }) as State
    expect(Date.now() - started).toBeLessThan(900)
    expect(state.settled).toBe(false)
    expect(state.lists).toEqual([expect.objectContaining({ id: 'customers', shown: 0, loading: true })])
    expect(await gateway.invoke('app.get_view_state', { timeoutMs: 100 })).toMatchObject({ settled: false, lists: [{ loading: true }] })
  })

  it('caps the timeout', async () => {
    fetchMs = null
    const started = Date.now()
    const state = await gateway.invoke('app.get_view_state', { waitFor: { list: 'customers', minRows: 1 }, timeoutMs: 60_000 }) as State
    expect(Date.now() - started).toBeLessThan(11_000)
    expect(state.settled).toBe(false)
  }, 15_000)

  it('wait "registered" returns once the view has registered, still loading', async () => {
    const state = await gateway.invoke('app.navigate', { view: 'customers', wait: 'registered' }) as State
    expect(state).toMatchObject({ settled: false, lists: [{ id: 'customers', shown: 0, loading: true }] })
  })

  it('wait "none" returns at once, the rows not there yet', async () => {
    const started = Date.now()
    const state = await gateway.invoke('app.navigate', { view: 'customers', wait: 'none' }) as State
    expect(Date.now() - started).toBeLessThan(100)
    expect(state.lists.every((list) => list.shown === 0)).toBe(true)
  })

  it('without a loading signal, "loaded" falls back to "registered"', async () => {
    reportsLoading = false
    const state = await gateway.invoke('app.navigate', { view: 'customers' }) as State
    expect(state).toMatchObject({ settled: true, lists: [{ shown: 0, loading: false }] })
  })

  it('without a loading signal, waitFor minRows waits for the rows', async () => {
    reportsLoading = false
    const state = await gateway.invoke('app.navigate', { view: 'customers', waitFor: { list: 'customers', minRows: 1 } }) as State
    expect(state).toMatchObject({ settled: true, lists: [{ shown: 2 }] })
  })

  it('waitFor that never holds times out with settled: false, not an error', async () => {
    reportsLoading = false
    const state = await gateway.invoke('app.navigate', { view: 'customers', waitFor: { minRows: 5 }, timeoutMs: 600 }) as State
    expect(state).toMatchObject({ settled: false, lists: [{ shown: 2 }] })
  })

  it('select waits as well', async () => {
    await gateway.invoke('app.navigate', { view: 'customers' })
    const state = await gateway.invoke('app.select', { id: 'c2', wait: 'loaded' }) as State & { query: Record<string, string> }
    expect(state).toMatchObject({ settled: true, query: { id: 'c2' } })
  })
})

describe('a form whose object arrives late', () => {
  it('navigate waits for the form, and waitFor names it', async () => {
    const state = await gateway.invoke('app.navigate', { view: 'orders', waitFor: { form: 'OrderForm' } }) as State
    expect(state).toMatchObject({ settled: true, forms: [{ id: 'OrderForm' }] })
  })
})

describe('leaving a view', () => {
  it('removes its actions', async () => {
    await gateway.invoke('app.navigate', { view: 'orders' })
    expect(gateway.capabilities.has('app.orders.approve')).toBe(true)
    const state = await gateway.invoke('app.navigate', { view: 'customers' }) as State
    expect(state.actions).toEqual([])
    expect(gateway.capabilities.has('app.orders.approve')).toBe(false)
    expect(registry.allActions()).toEqual([])
  })
})
