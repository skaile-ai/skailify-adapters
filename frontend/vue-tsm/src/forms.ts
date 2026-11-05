/**
 * UIModel forms for the agent: which fields a form has (from the UIModel's
 * widgets and the Ecore features they are bound to), what they hold, and
 * writing values into them the way a widget does.
 *
 * Reflective only (eGet/eSet over the features), so it works for any EPackage,
 * dynamic or generated. Values travel as JSON: enums by literal name,
 * references as `{ type, id, label }` and back by id (or label), amounts and
 * dates as the strings the model holds.
 */
import type { EObject, EStructuralFeature } from '@emfts/core'
import { bumpExpressionTick } from '@emfts/uimodel-composer'

/** One field of a form, as the agent sees it. */
export interface FieldInfo {
  /** The widget's name in the UIModel; what `form.set_fields` takes */
  name: string
  label: string
  /** The Ecore feature the widget is bound to */
  feature: string
  /** The feature's type: EString, EBigDecimal, an enum's or a class's name */
  type: string
  kind: 'attribute' | 'enum' | 'reference'
  many: boolean
  readOnly: boolean
  required: boolean
  value: unknown
  /** The literal names of an enum */
  options?: string[]
}

export interface FieldError {
  field: string
  message: string
}

/** Finds the objects of a type by id or label, for references; the app supplies it. */
export type ReferenceResolver = (typeName: string, key: string) => EObject | undefined

/** How an object is called in a list or a reference. */
export type Labeler = (object: EObject) => string

interface Widget {
  name: string
  label: string
  feature: EStructuralFeature
  readOnly: boolean
  required: boolean
}

interface Named {
  getName?: () => string
}

interface FeatureMeta extends Named {
  getEType?: () => (Named & { getELiterals?: () => Iterable<Named>; getEEnumLiteral?: (name: string) => unknown }) | null
  isMany?: () => boolean
  getLowerBound?: () => number
  isChangeable?: () => boolean
  isID?: () => boolean
  isContainment?: () => boolean
  getEReferenceType?: () => Named
}

const meta = (feature: EStructuralFeature) => feature as unknown as FeatureMeta

/** Reads an attribute of a UIModel element by name; undefined when the element has none. */
function attribute(element: EObject, name: string): unknown {
  const feature = element.eClass().getEStructuralFeature(name)
  return feature ? element.eGet(feature) : undefined
}

/** A boolean UIModel attribute; emf.ts may hand an XMI value over as the string it was read from. */
const flag = (value: unknown) => value === true || value === 'true'

function isStructuralFeature(value: unknown): value is EStructuralFeature {
  return !!value && typeof value === 'object' && typeof (value as FeatureMeta).getEType === 'function'
    && typeof (value as FeatureMeta).isMany === 'function'
}

/** The widgets of a UIModel that are bound to a feature, in document order. */
export function widgetsOf(uiModel: EObject): Widget[] {
  const widgets: Widget[] = []
  for (const element of uiModel.eAllContents()) {
    const feature = attribute(element, 'feature')
    if (!isStructuralFeature(feature)) continue
    const featureName = meta(feature).getName?.() ?? ''
    widgets.push({
      name: String(attribute(element, 'name') || featureName),
      label: String(attribute(element, 'label') || featureName),
      feature,
      readOnly: flag(attribute(element, 'readOnly')),
      required: flag(attribute(element, 'required')),
    })
  }
  return widgets
}

/** The name a form goes by: the UIModel's `name`, else `form`. */
export function formName(uiModel: EObject): string {
  return String(attribute(uiModel, 'name') || 'form')
}

function kindOf(feature: EStructuralFeature): FieldInfo['kind'] {
  if (typeof meta(feature).getEReferenceType === 'function') return 'reference'
  return typeof meta(feature).getEType?.()?.getELiterals === 'function' ? 'enum' : 'attribute'
}

/** The feature of the model object the widget stands for — looked up by name, so a subclass's own feature is found. */
function featureOn(model: EObject, widget: Widget): EStructuralFeature | undefined {
  const name = meta(widget.feature).getName?.() ?? ''
  return model.eClass().getEStructuralFeature(name) ?? undefined
}

/** The default label: `name`, `title`, `description`, else the ID attribute. */
export const defaultLabel: Labeler = (object) => {
  for (const name of ['name', 'title', 'label', 'description']) {
    const value = attribute(object, name)
    if (value != null && value !== '') return String(value)
  }
  return idOf(object)
}

/** The value of an object's ID attribute, '' without one. */
export function idOf(object: EObject): string {
  const id = (object.eClass() as unknown as { getEIDAttribute?: () => EStructuralFeature | null }).getEIDAttribute?.()
  if (id) return String(object.eGet(id) ?? '')
  const value = attribute(object, 'id')
  return value == null ? '' : String(value)
}

export function typeNameOf(object: EObject): string {
  return object.eClass().getName() ?? ''
}

function enumName(raw: unknown, feature: EStructuralFeature): string {
  const literals = Array.from(meta(feature).getEType?.()?.getELiterals?.() ?? [])
  if (raw && typeof raw === 'object') return (raw as Named).getName?.() ?? ''
  if (typeof raw === 'number') return literals[raw]?.getName?.() ?? ''
  if (typeof raw === 'string' && raw !== '') return raw
  // an unset enum holds its first literal, as in Java EMF
  return literals[0]?.getName?.() ?? ''
}

function plain(raw: unknown, feature: EStructuralFeature, label: Labeler): unknown {
  if (raw == null) return null
  switch (kindOf(feature)) {
    case 'enum':
      return enumName(raw, feature)
    case 'reference': {
      const target = raw as EObject
      return { type: typeNameOf(target), id: idOf(target), label: label(target) }
    }
    default:
      return typeof raw === 'object' ? String(raw) : raw
  }
}

/** The value of a feature as JSON. */
export function readValue(model: EObject, feature: EStructuralFeature, label: Labeler): unknown {
  const raw = model.eGet(feature)
  if (meta(feature).isMany?.()) return Array.from((raw ?? []) as Iterable<unknown>).map((each) => plain(each, feature, label))
  return kindOf(feature) === 'enum' ? enumName(raw, feature) : plain(raw, feature, label)
}

function isReadOnly(feature: EStructuralFeature, widget: Widget): boolean {
  const m = meta(feature)
  return widget.readOnly || (m.isChangeable ? !m.isChangeable() : false) || (m.isID?.() ?? false)
}

function isRequired(feature: EStructuralFeature, widget: Widget): boolean {
  return widget.required || (meta(feature).getLowerBound?.() ?? 0) > 0
}

/** The fields of a form with their current values. */
export function readFields(uiModel: EObject, model: EObject, label: Labeler): FieldInfo[] {
  return widgetsOf(uiModel).flatMap((widget) => {
    const feature = featureOn(model, widget)
    if (!feature) return []
    const kind = kindOf(feature)
    const type = meta(feature).getEType?.()
    const info: FieldInfo = {
      name: widget.name,
      label: widget.label,
      feature: meta(feature).getName?.() ?? '',
      type: type?.getName?.() ?? '',
      kind,
      many: meta(feature).isMany?.() ?? false,
      readOnly: isReadOnly(feature, widget),
      required: isRequired(feature, widget),
      value: readValue(model, feature, label),
    }
    if (kind === 'enum') info.options = Array.from(type?.getELiterals?.() ?? []).map((l) => l.getName?.() ?? '')
    return [info]
  })
}

const INTEGER = /^E(Int|Long|Short|Byte|IntegerObject|LongObject|ShortObject|ByteObject|BigInteger)$/
const FLOAT = /^E(Double|Float|DoubleObject|FloatObject)$/
const BOOLEAN = /^EBoolean(Object)?$/

/** Turns a JSON value into what the attribute holds, or a message why it can't. */
function convertAttribute(value: unknown, typeName: string): { ok: true; value: unknown } | { ok: false; message: string } {
  if (value === null || value === '') return { ok: true, value: null }
  if (BOOLEAN.test(typeName)) {
    if (typeof value === 'boolean') return { ok: true, value }
    if (value === 'true' || value === 'false') return { ok: true, value: value === 'true' }
    return { ok: false, message: `expects true or false, got ${JSON.stringify(value)}` }
  }
  if (INTEGER.test(typeName)) {
    const n = typeof value === 'number' ? value : Number(String(value).trim())
    return Number.isInteger(n) ? { ok: true, value: n } : { ok: false, message: `expects a whole number, got ${JSON.stringify(value)}` }
  }
  if (FLOAT.test(typeName)) {
    const n = typeof value === 'number' ? value : Number(String(value).trim())
    return Number.isFinite(n) ? { ok: true, value: n } : { ok: false, message: `expects a number, got ${JSON.stringify(value)}` }
  }
  if (typeName === 'EBigDecimal') {
    // exact decimal strings, as the XMI carries them
    const text = typeof value === 'number' ? String(value) : String(value).trim()
    return /^-?\d+(\.\d+)?$/.test(text)
      ? { ok: true, value: text }
      : { ok: false, message: `expects a decimal number with a point, e.g. "1190.00", got ${JSON.stringify(value)}` }
  }
  if (typeof value === 'object') return { ok: false, message: 'expects a single value, not an object or list' }
  return { ok: true, value: String(value) }
}

function convertOne(
  value: unknown,
  feature: EStructuralFeature,
  resolve: ReferenceResolver | undefined,
): { ok: true; value: unknown } | { ok: false; message: string } {
  const kind = kindOf(feature)
  const type = meta(feature).getEType?.()
  if (kind === 'enum') {
    if (value === null) return { ok: true, value: null }
    const names = Array.from(type?.getELiterals?.() ?? []).map((l) => l.getName?.() ?? '')
    const literal = typeof value === 'string' ? type?.getEEnumLiteral?.(value) : null
    return literal ? { ok: true, value: literal } : { ok: false, message: `expects one of ${names.join(', ')}` }
  }
  if (kind === 'reference') {
    if (value === null) return { ok: true, value: null }
    if (meta(feature).isContainment?.()) return { ok: false, message: 'contained objects cannot be set from here' }
    const key = typeof value === 'string' ? value : (value as { id?: unknown })?.id
    if (typeof key !== 'string' || key === '') return { ok: false, message: 'expects the id of an object' }
    const typeName = meta(feature).getEReferenceType?.().getName?.() ?? type?.getName?.() ?? ''
    const target = resolve?.(typeName, key)
    return target ? { ok: true, value: target } : { ok: false, message: `no ${typeName} with id or name ${JSON.stringify(key)}` }
  }
  return convertAttribute(value, type?.getName?.() ?? '')
}

/** Writes a value the way a widget does: eSet, then the composer's tick, so every widget shows it. */
export type ValueWriter = (model: EObject, feature: EStructuralFeature, value: unknown) => void

export const defaultWriter: ValueWriter = (model, feature, value) => {
  if (meta(feature).isMany?.()) {
    const list = model.eGet(feature) as unknown as { clear(): void; add(value: unknown): unknown }
    list.clear()
    for (const each of value as unknown[]) list.add(each)
  } else {
    model.eSet(feature, value as never)
  }
  bumpExpressionTick()
}

export interface SetFieldsResult {
  /** The fields written, by widget name */
  applied: string[]
  /** The fields refused, with the reason; nothing of them was written */
  errors: FieldError[]
}

/**
 * Writes the given values into the form's object. Every field is checked on its
 * own: valid ones are written, refused ones are reported and leave the model as
 * it was. Fields are addressed by the widget's name, its label or its feature.
 */
export function setFields(
  uiModel: EObject,
  model: EObject,
  values: Record<string, unknown>,
  options: { resolve?: ReferenceResolver; write?: ValueWriter } = {},
): SetFieldsResult {
  const widgets = widgetsOf(uiModel)
  const write = options.write ?? defaultWriter
  const result: SetFieldsResult = { applied: [], errors: [] }
  for (const [key, value] of Object.entries(values)) {
    const widget = widgets.find((w) => w.name === key)
      ?? widgets.find((w) => meta(w.feature).getName?.() === key)
      ?? widgets.find((w) => w.label === key)
    if (!widget) {
      result.errors.push({ field: key, message: `the form has no field ${key}; fields are ${widgets.map((w) => w.name).join(', ')}` })
      continue
    }
    const feature = featureOn(model, widget)
    if (!feature) {
      result.errors.push({ field: key, message: `a ${typeNameOf(model)} has no feature ${meta(widget.feature).getName?.()}` })
      continue
    }
    if (isReadOnly(feature, widget)) {
      result.errors.push({ field: widget.name, message: 'the field is read-only' })
      continue
    }
    let converted: { ok: true; value: unknown } | { ok: false; message: string }
    if (meta(feature).isMany?.()) {
      if (!Array.isArray(value)) {
        result.errors.push({ field: widget.name, message: 'expects a list' })
        continue
      }
      const each = value.map((v) => convertOne(v, feature, options.resolve))
      const failed = each.find((c) => !c.ok)
      converted = failed ?? { ok: true, value: each.map((c) => (c as { value: unknown }).value) }
    } else {
      converted = convertOne(value, feature, options.resolve)
    }
    if (!converted.ok) {
      result.errors.push({ field: widget.name, message: converted.message })
      continue
    }
    write(model, feature, converted.value)
    result.applied.push(widget.name)
  }
  return result
}

/** The required fields of the form that are empty. */
export function missingRequired(uiModel: EObject, model: EObject): FieldError[] {
  return widgetsOf(uiModel).flatMap((widget) => {
    const feature = featureOn(model, widget)
    if (!feature || !isRequired(feature, widget) || isReadOnly(feature, widget)) return []
    const raw = model.eGet(feature)
    const empty = meta(feature).isMany?.()
      ? Array.from((raw ?? []) as Iterable<unknown>).length === 0
      : raw == null || raw === ''
    // an enum always holds a literal (its first one when unset)
    return empty && kindOf(feature) !== 'enum' ? [{ field: widget.name, message: 'is required' }] : []
  })
}
