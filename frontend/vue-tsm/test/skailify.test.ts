/**
 * The generic capabilities end to end through the real app SDK, against a
 * fake host and gateway: handshake, registration, invocation, results.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { EObject } from '@emfts/core'
import { noopLogger } from '@skaile/workspaces/app-sdk'
import { AgentRegistry, startSkailify, z, type SkailifyHandle, type ViewDescriptor } from '../src/index.js'
import { startSkailify as startUnshared } from '../src/connect.js'
import { embedded, FakeGateway, standalone } from './fake-host.js'
import { create, get, orderForm } from './fixture.js'

const VIEWS: ViewDescriptor[] = [
  { id: 'orders', title: 'Orders', menu: true, objectTypes: ['Order'] },
  { id: 'customers', title: 'Customers', menu: true, objectTypes: ['Customer'] },
  { id: 'settings', title: 'Settings', menu: false, objectTypes: [] },
]

const customers = [create('Customer', { id: 'c1', name: 'ACME' }), create('Customer', { id: 'c2', name: 'Globex' })]
const resolveReference = (type: string, key: string) =>
  type === 'Customer' ? customers.find((c) => get(c, 'id') === key || get(c, 'name') === key) : undefined

let router: Router
let gateway: FakeGateway
let registry: AgentRegistry
let handle: SkailifyHandle

function start(extra: Record<string, unknown> = {}) {
  handle = startUnshared({
    appId: 'shop',
    views: () => VIEWS,
    router,
    registry,
    resolveReference,
    settleTimeoutMs: 300,
    logger: noopLogger,
    windowRef: embedded,
    resolveParams: gateway.resolveParams,
    createTransport: gateway.createTransport,
    ...extra,
  })
  return handle.ready
}

beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:path(.*)*', component: { render: () => null } }] })
  await router.push('/orders')
  gateway = new FakeGateway()
  registry = new AgentRegistry()
})

afterEach(async () => {
  await handle?.close()
})

describe('connecting', () => {
  it('does nothing standalone', async () => {
    const statuses: string[] = []
    const standaloneHandle = startSkailify({ appId: 'shop', views: () => VIEWS, router, windowRef: standalone, onStatus: (s) => statuses.push(s.state), resolveParams: gateway.resolveParams, createTransport: gateway.createTransport })
    await standaloneHandle.ready
    expect(standaloneHandle.app).toBeNull()
    expect(gateway.hellos).toEqual([])
    expect(statuses).toEqual(['standalone'])
    await standaloneHandle.close()
  })

  it('says hello as the app and registers the generic capabilities', async () => {
    await start()
    expect(gateway.hellos).toEqual([{ appId: 'shop' }])
    expect(gateway.tokens).toEqual(['token-1'])
    expect([...gateway.capabilities.keys()]).toEqual([
      'app.list_views', 'app.get_view_state', 'app.navigate', 'app.open', 'app.select', 'app.form.set_fields', 'app.form.submit',
    ])
    expect(gateway.capabilities.get('app.get_view_state')!.kind).toBe('query')
    expect(gateway.capabilities.get('app.navigate')!.kind).toBe('effect')
  })

  it('puts the capability prefix in one place', async () => {
    await start({ capabilityPrefix: 'shop' })
    expect([...gateway.capabilities.keys()][0]).toBe('app.shop.list_views')
  })

  it('reconnects with a fresh token from the host', async () => {
    await start()
    gateway.drop()
    await new Promise((resolve) => setTimeout(resolve, 1500))
    expect(gateway.tokens).toEqual(['token-1', 'token-2'])
    expect(gateway.hellos).toHaveLength(2)
    expect(gateway.capabilities.has('app.form.submit')).toBe(true)
  }, 5000)
})

describe('views and navigation', () => {
  beforeEach(() => start())

  it('lists the views and the current one', async () => {
    expect(await gateway.invoke('app.list_views')).toEqual({ current: 'orders', views: VIEWS })
  })

  it('navigates to a view with an object selected', async () => {
    const state = await gateway.invoke('app.navigate', { view: 'customers', objectId: 'c2' }) as Record<string, unknown>
    expect(router.currentRoute.value.fullPath).toBe('/customers?id=c2')
    expect(state).toMatchObject({ view: { id: 'customers', title: 'Customers' }, selected: { id: 'c2' } })
  })

  it('refuses an unknown view with the known ones', async () => {
    expect(await gateway.invoke('app.navigate', { view: 'nope' })).toEqual({ error: 'no view nope; views: orders, customers, settings' })
  })

  it('opens an object in the view that shows its type', async () => {
    await gateway.invoke('app.open', { type: 'Customer', id: 'c1' })
    expect(router.currentRoute.value.fullPath).toBe('/customers?id=c1')
    expect(await gateway.invoke('app.open', { type: 'Invoice', id: 'i1' })).toEqual({ error: 'no view opens Invoice; types with a view: Order, Customer' })
  })
})

describe('lists', () => {
  beforeEach(() => start({ maxRows: 1 }))

  it('shows visible rows capped, with ids and labels', async () => {
    registry.registerList({ id: 'customers', rows: () => customers, total: () => 5 })
    const state = await gateway.invoke('app.get_view_state') as { lists: unknown[] }
    expect(state.lists).toEqual([{ id: 'customers', type: 'Customer', shown: 2, total: 5, rows: [{ id: 'c1', label: 'ACME' }], truncated: true, loading: false }])
  })

  it('selects a row through the route by default', async () => {
    registry.registerList({ rows: () => customers })
    await gateway.invoke('app.select', { id: 'c2' })
    expect(router.currentRoute.value.fullPath).toBe('/orders?id=c2')
  })

  it("selects a row the view's own way", async () => {
    const picked: EObject[] = []
    registry.registerList({ rows: () => customers, select: (row) => { picked.push(row) } })
    await gateway.invoke('app.select', { id: 'c1' })
    expect(picked).toEqual([customers[0]])
    expect(await gateway.invoke('app.select', { id: 'c9' })).toEqual({ error: 'no row c9 is shown; a search or filter may hide it' })
  })
})

describe('forms', () => {
  let order: EObject
  let saved: EObject[]
  let refusal: string

  beforeEach(async () => {
    await start()
    order = create('Order', { id: 'o1' })
    saved = []
    refusal = ''
    registry.registerForm({
      uiModel: orderForm(),
      model: () => order,
      title: 'Order',
      messages: () => [refusal],
      submit: async () => {
        if (get(order, 'total') === '0') {
          refusal = 'total must be positive'
          return false
        }
        saved.push(order)
        return true
      },
    })
  })

  it('shows the open form with fields, values and what is missing', async () => {
    const state = await gateway.invoke('app.get_view_state') as { forms: Array<Record<string, unknown>>; selected: unknown }
    expect(state.selected).toEqual({ type: 'Order', id: 'o1', label: 'o1' })
    expect(state.forms[0]).toMatchObject({ id: 'OrderForm', type: 'Order', objectId: 'o1', canSubmit: true, messages: [] })
    expect(state.forms[0].missing).toEqual([{ field: 'number', message: 'is required' }, { field: 'customer', message: 'is required' }])
  })

  it('fills fields and reports the refused ones', async () => {
    const result = await gateway.invoke('app.form.set_fields', { fields: { number: 'A-1', customer: 'ACME', urgent: 'yes' } })
    expect(result).toEqual({
      form: 'OrderForm',
      applied: ['number', 'customer'],
      errors: [{ field: 'urgent', message: 'expects true or false, got "yes"' }],
      missing: [],
    })
  })

  it('does not submit with required fields empty', async () => {
    expect(await gateway.invoke('app.form.submit', {})).toEqual({
      form: 'OrderForm', ok: false, objectId: 'o1',
      errors: [{ field: 'number', message: 'is required' }, { field: 'customer', message: 'is required' }],
    })
    expect(saved).toEqual([])
  })

  it("returns the app's refusal, then saves once fixed", async () => {
    await gateway.invoke('app.form.set_fields', { fields: { number: 'A-1', customer: 'c1', total: '0' } })
    expect(await gateway.invoke('app.form.submit', {})).toEqual({ form: 'OrderForm', ok: false, objectId: 'o1', errors: [{ message: 'total must be positive' }] })
    await gateway.invoke('app.form.set_fields', { fields: { total: '12.50' } })
    expect(await gateway.invoke('app.form.submit', { form: 'OrderForm' })).toEqual({ form: 'OrderForm', ok: true, objectId: 'o1', errors: [] })
    expect(saved).toEqual([order])
  })

  it('names the open forms when the one asked for is not there', async () => {
    expect(await gateway.invoke('app.form.submit', { form: 'Other' })).toEqual({ error: 'no open form Other; open forms: OrderForm' })
  })
})

describe('actions of a view', () => {
  beforeEach(() => start())

  it('registers and removes them while connected, approval flag included', async () => {
    const remove = registry.registerAction({
      name: 'orders.approve',
      description: 'Approve an order',
      kind: 'effect',
      requiresApproval: true,
      input: z.object({ orderId: z.string() }),
      handler: async ({ orderId }) => ({ approved: orderId }),
    })
    expect(gateway.capabilities.get('app.orders.approve')).toMatchObject({ requiresApproval: true, kind: 'effect' })
    expect(await gateway.invoke('app.orders.approve', { orderId: 'o1' })).toEqual({ approved: 'o1' })
    expect((await gateway.invoke('app.get_view_state') as { actions: string[] }).actions).toEqual(['app.orders.approve'])
    remove()
    expect(gateway.capabilities.has('app.orders.approve')).toBe(false)
  })
})
