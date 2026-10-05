import type { ApiAssetConsent } from '@renderer/generated/api-asset-consent'
import type { ApiAssetConsentStart } from '@renderer/generated/api-asset-consent-start'
import type { ApiAssetSnapshot } from '@renderer/generated/api-asset-snapshot'
import type { Client } from '../client/Client'

/** 자산 조회(F16)와 금융결제원 동의. 동의 주소는 기본 브라우저로 열고 콜백은 데몬이 받음. */
export function assetsApi(client: Client) {
  return {
    assets: () => client.request<ApiAssetSnapshot>('GET', '/assets'),
    consent: () => client.request<ApiAssetConsent>('GET', '/assets/consent'),
    startConsent: () => client.request<ApiAssetConsentStart>('POST', '/assets/consent', {}),
    revokeConsent: () => client.request<null>('DELETE', '/assets/consent'),
  }
}
