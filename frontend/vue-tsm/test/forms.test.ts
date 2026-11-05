import { describe, expect, it } from 'vitest'
import { missingRequired, readFields, setFields, widgetsOf } from '../src/forms.js'
import { create, get, orderForm } from './fixture.js'

const label = (o: { eClass(): { getEStructuralFeature(n: string): unknown } } & object) =>
  String((o as never as { eGet(f: unknown): unknown }).eGet(o.eClass().getEStructuralFeature('name')) ?? '')

describe('UIModel fields', () => {
  it('finds the widgets bound to features, in order', () => {
    expect(widgetsOf(orderForm()).map((w) => w.name)).toEqual(['id', 'number', 'total', 'items', 'urgent', 'state', 'customer'])
  })

  it('reads values as JSON: enums by literal, references as type/id/label', () => {
    const acme = create('Customer', { id: 'c1', name: 'ACME' })
    const order = create('Order', { id: 'o1', number: 'A-17', total: '119.00', customer: acme })
    const fields = Object.fromEntries(readFields(orderForm(), order, label as never).map((f) => [f.name, f]))
    expect(fields.number).toMatchObject({ label: 'Order number', type: 'EString', kind: 'attribute', required: true, value: 'A-17' })
    expect(fields.state).toMatchObject({ kind: 'enum', value: 'DRAFT', options: ['DRAFT', 'OPEN', 'DONE'], readOnly: true })
    expect(fields.customer).toMatchObject({ kind: 'reference', type: 'Customer', required: true, value: { type: 'Customer', id: 'c1', label: 'ACME' } })
    expect(fields.id.readOnly).toBe(true)
  })
})

describe('setFields', () => {
  const customers = [create('Customer', { id: 'c1', name: 'ACME' }), create('Customer', { id: 'c2', name: 'Globex' })]
  const resolve = (type: string, key: string) =>
    type === 'Customer' ? customers.find((c) => get(c, 'id') === key || get(c, 'name') === key) : undefined

  it('writes typed values and resolves references by id or name', () => {
    const order = create('Order')
    const result = setFields(orderForm(), order, { number: 'B-1', total: 1190.5, items: '3', urgent: 'true', customer: 'Globex' }, { resolve })
    expect(result).toEqual({ applied: ['number', 'total', 'items', 'urgent', 'customer'], errors: [] })
    expect(get(order, 'total')).toBe('1190.5')
    expect(get(order, 'items')).toBe(3)
    expect(get(order, 'urgent')).toBe(true)
    expect(get(get(order, 'customer') as never, 'id')).toBe('c2')
  })

  it('addresses fields by label and feature too', () => {
    const order = create('Order')
    expect(setFields(orderForm(), order, { 'Order number': 'X' }).applied).toEqual(['number'])
  })

  it('reports each refused field and leaves it unchanged', () => {
    const order = create('Order', { number: 'keep', total: '1.00' })
    const result = setFields(orderForm(), order, { total: '1.190,00', items: 'many', state: 'OPEN', id: 'x', nope: 1, customer: 'c9', number: 'new' }, { resolve })
    expect(result.applied).toEqual(['number'])
    expect(result.errors.map((e) => e.field)).toEqual(['total', 'items', 'state', 'id', 'nope', 'customer'])
    expect(result.errors.find((e) => e.field === 'state')!.message).toBe('the field is read-only')
    expect(result.errors.find((e) => e.field === 'customer')!.message).toContain('no Customer')
    expect(get(order, 'total')).toBe('1.00')
  })

  it('names the empty required fields', () => {
    const order = create('Order')
    expect(missingRequired(orderForm(), order).map((e) => e.field)).toEqual(['number', 'customer'])
  })
})
