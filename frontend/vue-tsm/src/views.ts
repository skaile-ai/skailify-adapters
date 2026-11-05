/**
 * The views an app has, as the agent sees them. Derived from the app's view
 * registry; how a view service is read (its service id, which property is the
 * path, …) is configuration, so any tsm app can plug its own contract in.
 */
import type { ModuleContext, ServiceReference } from '@eclipse-daanse/tsm'

export interface ViewDescriptor {
  /** The view's id, which is also its path: `orders` is shown under `/orders` */
  id: string
  title: string
  /** false for views reached from another view only */
  menu: boolean
  /** The object types the view opens (EClass names); what `open` looks for */
  objectTypes: string[]
}

/** Where the descriptors come from; called on every request, so it always answers the current set. */
export type ViewSource = () => ViewDescriptor[]

/** How a view service of the app's contract is read. Every field has the default of the tsm view contract below. */
export interface TsmViewContract {
  /** The service property holding the view id / path; default `view` */
  pathProperty?: string
  /** The service property sorting the views; default `order` */
  orderProperty?: string
  /** The service property that is false for views off the menu; default `menu` */
  menuProperty?: string
  /** The service property listing the object types the view opens; default `objectTypes` */
  objectTypesProperty?: string
  /** The view's title from the service instance; default its `title` */
  title?: (service: unknown) => string
}

/** A view source over the services registered under `serviceId` in the tsm registry. */
export function tsmViewSource(context: ModuleContext, serviceId: string, contract: TsmViewContract = {}): ViewSource {
  const path = contract.pathProperty ?? 'view'
  const order = contract.orderProperty ?? 'order'
  const menu = contract.menuProperty ?? 'menu'
  const types = contract.objectTypesProperty ?? 'objectTypes'
  const title = contract.title ?? ((service: unknown) => String((service as { title?: unknown })?.title ?? ''))
  const sortKey = (reference: ServiceReference) => Number(reference.properties[order] ?? 0)
  return () =>
    context.services
      .getServiceReferences(serviceId)
      .sort((left, right) => sortKey(left) - sortKey(right))
      .flatMap((reference) => {
        const properties = reference.properties as Record<string, unknown>
        const id = properties[path]
        if (typeof id !== 'string') return []
        const service = context.services.resolveReference(reference)
        const objectTypes = properties[types]
        return [{
          id,
          title: title(service) || id,
          menu: properties[menu] !== false,
          objectTypes: Array.isArray(objectTypes) ? objectTypes.map(String) : [],
        }]
      })
}
