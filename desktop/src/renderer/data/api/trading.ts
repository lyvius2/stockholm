import type { ApiCancelPlacement } from '@renderer/generated/api-cancel-placement'
import type { ApiConditionalCancel } from '@renderer/generated/api-conditional-cancel'
import type { ApiConditionalOrderPlacement } from '@renderer/generated/api-conditional-order-placement'
import type { ApiConditionalOrderRequest } from '@renderer/generated/api-conditional-order-request'
import type { ApiConditionalOrders } from '@renderer/generated/api-conditional-orders'
import type { ApiOrderAmendment } from '@renderer/generated/api-order-amendment'
import type { ApiOrderPlacement } from '@renderer/generated/api-order-placement'
import type { ApiOrderRequest } from '@renderer/generated/api-order-request'
import type { ApiOrderTicket } from '@renderer/generated/api-order-ticket'
import type { Client } from '../client/Client'
import type { StreamSymbol } from '../stream/MarketStream'
import { query } from './market'

export type ConditionalScope = 'OPEN' | 'CLOSED'

/**
 * 수동 주문·정정·취소와 조건주문.
 * 멱등 키(clientOrderId)는 화면이 버튼을 누를 때 한 번 만들어 재시도에도 같은 값을 보냄.
 */
export function tradingApi(client: Client) {
  return {
    ticket: (symbol: StreamSymbol) =>
      client.request<ApiOrderTicket>(
        'GET',
        `/orders/ticket?${query({ market: symbol.market, code: symbol.code })}`,
      ),
    place: (request: ApiOrderRequest) =>
      client.request<ApiOrderPlacement>('POST', '/orders', request),
    amend: (brokerOrderId: string, request: ApiOrderAmendment) =>
      client.request<ApiOrderPlacement>(
        'POST',
        `/orders/${encodeURIComponent(brokerOrderId)}/amend`,
        request,
      ),
    cancel: (brokerOrderId: string) =>
      client.request<ApiCancelPlacement>(
        'POST',
        `/orders/${encodeURIComponent(brokerOrderId)}/cancel`,
        {},
      ),
    conditionalOrders: (scope: ConditionalScope, symbol?: StreamSymbol, cursor?: string) =>
      client.request<ApiConditionalOrders>(
        'GET',
        `/conditional-orders?${query({ scope, market: symbol?.market, code: symbol?.code, cursor })}`,
      ),
    registerConditional: (request: ApiConditionalOrderRequest) =>
      client.request<ApiConditionalOrderPlacement>('POST', '/conditional-orders', request),
    amendConditional: (conditionalOrderId: string, request: ApiConditionalOrderRequest) =>
      client.request<ApiConditionalOrderPlacement>(
        'POST',
        `/conditional-orders/${encodeURIComponent(conditionalOrderId)}/amend`,
        request,
      ),
    cancelConditional: (conditionalOrderId: string) =>
      client.request<ApiConditionalCancel>(
        'POST',
        `/conditional-orders/${encodeURIComponent(conditionalOrderId)}/cancel`,
        {},
      ),
  }
}
