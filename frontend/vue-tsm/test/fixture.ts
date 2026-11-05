/**
 * A small model of its own — nothing app-specific in this package — and a UIModel
 * form over it, loaded the way an app loads its forms.
 */
import { BasicResourceSet, EPackageRegistry, URI, XMIResourceFactory, registerEcorePackage } from '@emfts/core'
import type { EClass, EObject, EPackage, XMIResource } from '@emfts/core'
import { UimodelFactory, UimodelPackage } from '@emfts/uimodel-composer'

export const NS = 'urn:skailify:test'

const ECORE = `<?xml version="1.0" encoding="UTF-8"?>
<ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" name="shop" nsURI="${NS}" nsPrefix="shop">
  <eClassifiers xsi:type="ecore:EEnum" name="State">
    <eLiterals name="DRAFT"/>
    <eLiterals name="OPEN" value="1"/>
    <eLiterals name="DONE" value="2"/>
  </eClassifiers>
  <eClassifiers xsi:type="ecore:EClass" name="Customer">
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="id" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString" iD="true"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="name" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
  </eClassifiers>
  <eClassifiers xsi:type="ecore:EClass" name="Order">
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="id" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString" iD="true"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="number" lowerBound="1" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="total" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EBigDecimal"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="items" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EInt"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="urgent" eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EBoolean"/>
    <eStructuralFeatures xsi:type="ecore:EAttribute" name="state" eType="#//State"/>
    <eStructuralFeatures xsi:type="ecore:EReference" name="customer" eType="#//Customer"/>
  </eClassifiers>
</ecore:EPackage>`

const FORM = `<?xml version="1.0" encoding="UTF-8"?>
<uimodel:UIModel xmlns:xmi="http://www.omg.org/XMI" xmi:version="2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:uimodel="http://uimodel/1.0" name="OrderForm">
  <targetClasses href="${NS}#//Order"/>
  <components xsi:type="uimodel:FormView" name="OrderFormView">
    <fields xsi:type="uimodel:InputWidget" name="id" label="Id" feature="${NS}#//Order/id"/>
    <fields xsi:type="uimodel:InputWidget" name="number" label="Order number" feature="${NS}#//Order/number"/>
    <fields xsi:type="uimodel:InputWidget" name="total" label="Total" feature="${NS}#//Order/total"/>
    <fields xsi:type="uimodel:InputWidget" name="items" label="Items" feature="${NS}#//Order/items" readOnly="false"/>
    <fields xsi:type="uimodel:CheckboxWidget" name="urgent" label="Urgent" feature="${NS}#//Order/urgent"/>
    <fields xsi:type="uimodel:SelectWidget" name="state" label="State" feature="${NS}#//Order/state" readOnly="true"/>
    <fields xsi:type="uimodel:SelectWidget" name="customer" label="Customer" feature="${NS}#//Order/customer" required="true"/>
  </components>
</uimodel:UIModel>`

function resourceSet(): BasicResourceSet {
  const set = new BasicResourceSet()
  const xmi = new XMIResourceFactory()
  set.getResourceFactoryRegistry().getExtensionToFactoryMap().set('*', xmi)
  return set
}

let pkg: EPackage | undefined
let form: EObject | undefined

export function shop(): EPackage {
  if (pkg) return pkg
  registerEcorePackage()
  const resource = resourceSet().createResource(URI.createURI('shop.ecore')) as XMIResource
  resource.loadFromString(ECORE)
  pkg = Array.from(resource.getContents())[0] as EPackage
  EPackageRegistry.INSTANCE.set(NS, pkg)
  return pkg
}

export function orderForm(): EObject {
  if (form) return form
  shop()
  const uimodel = UimodelPackage.eINSTANCE
  uimodel.setEFactoryInstance(UimodelFactory.eINSTANCE)
  EPackageRegistry.INSTANCE.set(uimodel.getNsURI()!, uimodel as unknown as EPackage)
  const resource = resourceSet().createResource(URI.createURI('order.uimodel.xmi')) as XMIResource
  resource.loadFromString(FORM)
  form = Array.from(resource.getContents())[0]
  return form!
}

export function create(type: string, values: Record<string, unknown> = {}): EObject {
  const eClass = shop().getEClassifier(type) as EClass
  const object = shop().getEFactoryInstance().create(eClass)
  for (const [name, value] of Object.entries(values)) object.eSet(eClass.getEStructuralFeature(name)!, value as never)
  return object
}

export const get = (object: EObject, name: string) => object.eGet(object.eClass().getEStructuralFeature(name)!)
