import type { Resolution } from '@renderer/generated/api-chart'
import type { SimpleRange } from '@renderer/generated/api-chart-view'
import { useChartViewStore } from '@renderer/data/store/chartView'
import { useStockStore } from '@renderer/data/store/stock'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { AVERAGE_WINDOWS, PriceChart } from './PriceChart'
import {
  DETAILED_BAR_COUNT,
  MINUTE_RESOLUTIONS,
  RESOLUTION_LABEL,
  SIMPLE_RANGE_LABEL,
  SIMPLE_RANGE_REQUESTS,
  isIntraday,
  type RangeRequest,
} from './chartData'
import { useChartData } from './useChartData'

const SIMPLE_RANGES = Object.keys(SIMPLE_RANGE_LABEL) as SimpleRange[]
const DAILY_RESOLUTIONS: readonly Resolution[] = ['DAY', 'WEEK', 'MONTH', 'YEAR']

/**
 * 1번 차트 영역(F3).
 * 헤더 한 줄에 "자세한 차트" 토글 · 모드별 기간 탭 · 실시간/지연 · 이동평균 범례만 둠(도구 팔레트 없음).
 * 토글과 모드별 마지막 기간 탭은 사용자 설정으로 저장됨.
 */
export function ChartArea() {
  const symbol = useStockStore((s) => s.current)
  if (symbol === null) return <p className="placeholder">종목을 고르면 차트가 보입니다</p>
  return <ChartOf symbol={symbol} />
}

function ChartOf({ symbol }: { readonly symbol: StreamSymbol }) {
  const view = useChartViewStore((s) => s.view)
  const change = useChartViewStore((s) => s.change)
  const request: RangeRequest = view.isDetailed
    ? { resolution: view.detailedResolution, count: DETAILED_BAR_COUNT }
    : SIMPLE_RANGE_REQUESTS[view.simpleRange]
  const data = useChartData(symbol, request)

  return (
    <div className="chart-area">
      <header className="chart-head">
        <label className="chart-toggle">
          <input
            type="checkbox"
            checked={view.isDetailed}
            onChange={(e) => void change({ isDetailed: e.target.checked })}
          />
          <span>자세한 차트</span>
        </label>
        {view.isDetailed ? (
          <div className="tab-bar" role="tablist" aria-label="봉 단위">
            <select
              aria-label="분봉"
              value={isIntraday(view.detailedResolution) ? view.detailedResolution : ''}
              onChange={(e) => {
                if (e.target.value !== '')
                  void change({ detailedResolution: e.target.value as Resolution })
              }}
            >
              <option value="">분 ▾</option>
              {MINUTE_RESOLUTIONS.map((resolution) => (
                <option key={resolution} value={resolution}>
                  {RESOLUTION_LABEL[resolution]}
                </option>
              ))}
            </select>
            {DAILY_RESOLUTIONS.map((resolution) => (
              <button
                key={resolution}
                type="button"
                role="tab"
                aria-selected={view.detailedResolution === resolution}
                onClick={() => void change({ detailedResolution: resolution })}
              >
                {RESOLUTION_LABEL[resolution]}
              </button>
            ))}
          </div>
        ) : (
          <div className="tab-bar" role="tablist" aria-label="기간">
            {SIMPLE_RANGES.map((range) => (
              <button
                key={range}
                type="button"
                role="tab"
                aria-selected={view.simpleRange === range}
                onClick={() => void change({ simpleRange: range })}
              >
                {SIMPLE_RANGE_LABEL[range]}
              </button>
            ))}
          </div>
        )}
        <span className="spacer" />
        {data.isDelayed ? (
          <span className="chip warn-chip">지연</span>
        ) : (
          <span className="chip live-chip">실시간</span>
        )}
        <span className="small attribution" aria-label="차트 라이브러리 귀속">
          TradingView Lightweight Charts™
        </span>
        {view.isDetailed && (
          <span className="ma-legend" aria-label="이동평균선">
            이동평균선
            {AVERAGE_WINDOWS.map((average) => (
              <b key={average.window} style={{ color: `var(${average.token})` }}>
                {average.window}
              </b>
            ))}
          </span>
        )}
      </header>
      {data.isError ? (
        <p className="warn chart-empty">차트를 받지 못했습니다</p>
      ) : (
        <PriceChart
          symbol={symbol}
          resolution={request.resolution}
          chart={data.chart}
          bars={data.bars}
          isDetailed={view.isDetailed}
        />
      )}
    </div>
  )
}
