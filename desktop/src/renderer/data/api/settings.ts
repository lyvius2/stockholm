import type { ApiChartView } from '@renderer/generated/api-chart-view'
import type { Client } from '../client/Client'

/** 사용자 설정. 세션 사용자의 것만 오가며 디바이스 간 동기화됨. */
export function settingsApi(client: Client) {
  return {
    chartView: () => client.request<ApiChartView>('GET', '/session/chart-view'),
    saveChartView: (view: ApiChartView) =>
      client.request<ApiChartView>('PUT', '/session/chart-view', view),
  }
}
