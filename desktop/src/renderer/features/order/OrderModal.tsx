import { useEffect, useRef, useState } from 'react'
import type { ApiOrderPlacement } from '@renderer/generated/api-order-placement'
import type { ApiOrderRequest, TimeInForce } from '@renderer/generated/api-order-request'
import type { ApiOrderTicket } from '@renderer/generated/api-order-ticket'
import { tradingApi } from '@renderer/data/api/trading'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'
import {
  pendingKey,
  useOrderUiStore,
  type OrderSide,
  type PendingPlace,
} from '@renderer/data/store/orderUi'
import { formatDecimal, formatMoney } from '@renderer/shared/format/decimal'
import { newUlid } from '@renderer/shared/id/ulid'

export type { OrderSide }

interface OrderModalProps {
  readonly symbol: StreamSymbol
  readonly side: OrderSide
  readonly ticket: ApiOrderTicket | null
  /** 응답을 못 받았던 요청을 이어서 확인할 때. 같은 멱등 키만 씀. */
  readonly resume?: PendingPlace | undefined
  readonly onClose: () => void
  readonly onPlaced: (placement: ApiOrderPlacement) => void
}

/** 확인 창에 보일 내용. `notes` 는 가드레일이 사람의 확인을 요구한 노트(규칙: 사유). */
interface Confirmation {
  readonly request: ApiOrderRequest
  readonly notes: readonly string[]
}

const SIDE_LABEL: Record<OrderSide, string> = { BUY: '매수', SELL: '매도' }
const TIME_IN_FORCE_OPTIONS: Record<StreamSymbol['market'], readonly TimeInForce[]> = {
  KR: ['DAY', 'OPG'],
  US: ['DAY', 'CLS'],
}
const TIME_IN_FORCE_LABEL: Record<TimeInForce, string> = {
  DAY: '당일',
  OPG: '시가 단일가',
  CLS: '종가 지정가',
}
const STATE_LABEL: Record<ApiOrderPlacement['state'], string> = {
  ACCEPTED: '접수됨',
  PENDING: '확인 중 — 결과를 조회하고 있습니다',
  NEEDS_REVIEW: '사람 확인 필요 — 토스에서 주문 여부를 확인하세요',
}

/**
 * 주문 모달(F1).
 * 바깥은 흐리지 않고 테두리만 매수 빨강·매도 파랑으로 빛남.
 * "지정가" 는 입력 가격·수량으로 확인 창을 거치고, "현재가 즉시" 는 확인 없이 그 순간의 현재가 지정가로 냄.
 * 즉시 주문도 가드레일을 우회하지 못함: 데몬이 확인을 요구하면(428) 확인 창이 뜨고 같은 멱등 키로 다시 냄.
 * 보내는 중·결과 모름 요청은 모달 밖 store 에 남아 모달이 닫혀도 같은 키로만 확인함.
 */
export function OrderModal({ symbol, side, ticket, resume, onClose, onPlaced }: OrderModalProps) {
  const live = useLiveQuote(symbol)
  const currency = symbol.market === 'KR' ? 'KRW' : 'USD'
  const [price, setPrice] = useState('')
  const [quantity, setQuantity] = useState(resume?.request.quantity ?? '')
  const [timeInForce, setTimeInForce] = useState<TimeInForce>('DAY')
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ text: string; isLocking: boolean } | null>(null)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [result, setResult] = useState<ApiOrderPlacement | null>(null)
  // 보냈는데 응답을 받지 못한 요청. 새 주문을 막고 같은 멱등 키로만 다시 확인함
  const [unresolved, setUnresolved] = useState<ApiOrderRequest | null>(resume?.request ?? null)
  // 멱등 키는 제출마다 한 번 만들고, 확인 창을 거쳐 다시 보낼 때도 같은 값을 씀
  const submissionKey = useRef<string | null>(resume?.request.clientOrderId ?? null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const currentPrice = live.quote?.last.amount ?? null
  const isInputLocked = busy || result !== null || unresolved !== null
  const isSubmitLocked = isInputLocked || error?.isLocking === true

  function buildRequest(limitPrice: string, confirmedRules: string[] = []): ApiOrderRequest {
    submissionKey.current ??= newUlid()
    return {
      clientOrderId: submissionKey.current,
      symbol,
      side,
      kind: 'LIMIT',
      timeInForce,
      limitPrice: { amount: limitPrice, currency },
      quantity: quantity.trim(),
      confirmedRules,
    }
  }

  function validate(limitPrice: string | null): string | null {
    if (limitPrice === null || limitPrice === '') return '가격이 없습니다'
    if (!/^\d+(\.\d+)?$/.test(limitPrice)) return '가격은 숫자여야 합니다'
    if (!/^\d+$/.test(quantity.trim()) || /^0+$/.test(quantity.trim()))
      return '수량은 1주 이상 정수여야 합니다'
    return null
  }

  async function place(request: ApiOrderRequest) {
    const store = useOrderUiStore.getState()
    const key = pendingKey('place', request.clientOrderId)
    store.begin({ kind: 'place', key, request, state: 'sending' })
    setBusy(true)
    setError(null)
    setSuggestions([])
    try {
      const placement = await tradingApi(localClient).place(request)
      useOrderUiStore.getState().resolve(key)
      submissionKey.current = null
      setUnresolved(null)
      setConfirmation(null)
      setResult(placement)
      onPlaced(placement)
    } catch (e) {
      if (!(e instanceof ApiError)) {
        // 데몬이 받았는지 모름. 같은 키로 다시 보내면 데몬이 첫 결과를 돌려줌(새 주문이 생기지 않음)
        useOrderUiStore.getState().markUnresolved(key)
        setUnresolved(request)
        setConfirmation(null)
        setError({
          text: '응답을 받지 못했습니다. 주문이 접수됐을 수 있으니 같은 주문으로 결과를 확인하세요.',
          isLocking: false,
        })
        return
      }
      // 데몬이 답했으면 주문은 나가지 않은 것이라 결과 모름 상태를 풂
      useOrderUiStore.getState().resolve(key)
      setUnresolved(null)
      if (e.status === 428 && e.code === 'ConfirmationRequiredException') {
        setConfirmation({ request, notes: e.details.notes ?? [] })
      } else if (e.code === 'GuardrailViolationException') {
        setError({
          text: `가드레일이 막았습니다: ${(e.details.violations ?? [e.message]).join(' · ')}`,
          isLocking: true,
        })
      } else if (e.code === 'OrderRejectedException') {
        setError({ text: `증권사가 거부했습니다: ${e.message}`, isLocking: false })
        setSuggestions(e.details.nearestPrices ?? [])
      } else {
        setError({ text: e.message, isLocking: false })
      }
    } finally {
      setBusy(false)
    }
  }

  // 지정가: 입력값으로 확인 창
  function submitLimit() {
    const limitPrice = price.trim() === '' ? currentPrice : price.trim()
    const problem = validate(limitPrice)
    if (problem !== null || limitPrice === null) {
      setError({ text: problem ?? '가격이 없습니다', isLocking: false })
      return
    }
    setError(null)
    setConfirmation({ request: buildRequest(limitPrice), notes: [] })
  }

  // 현재가 즉시: 확인 없이 그 순간의 현재가 지정가. 값이 지금 것인지 모르면 내지 않음
  function submitNow() {
    if (!live.isFresh) {
      setError({
        text: '현재가가 지연돼 즉시 주문을 낼 수 없습니다. 지정가로 내세요.',
        isLocking: false,
      })
      return
    }
    const problem = validate(currentPrice)
    if (problem !== null || currentPrice === null) {
      setError({ text: problem ?? '현재가를 아직 받지 못했습니다', isLocking: false })
      return
    }
    void place(buildRequest(currentPrice))
  }

  function confirmAndSend() {
    if (confirmation === null) return
    const rules = confirmation.notes.map((note) => note.split(':')[0]?.trim() ?? note)
    void place({ ...confirmation.request, confirmedRules: rules })
  }

  // 입력이 바뀌면 이전 오류·확인 창은 무효. 결과를 모르는 요청이 없을 때만 새 멱등 키를 씀
  function onInputChanged() {
    setError(null)
    setConfirmation(null)
    if (unresolved === null) submissionKey.current = null
  }

  const label = SIDE_LABEL[side]
  return (
    <div className="order-modal" role="dialog" aria-label={`${label} 주문`} data-side={side}>
      <header className="order-modal-title">
        <strong>{label} 주문</strong>
        <span className="num">{symbol.code}</span>
        <span className="spacer" />
        <span className="price-label">현재가</span>
        <span className="num live-price" aria-label="모달 현재가">
          {currentPrice === null ? '—' : formatMoney(currentPrice, currency)}
        </span>
        {live.isDelayed && <span className="chip warn-chip">지연</span>}
        <button type="button" aria-label="닫기" onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="order-form">
        <label className="field">
          <span className="field-label">지정가 ({currency})</span>
          <input
            inputMode="decimal"
            placeholder={
              currentPrice === null ? '현재가 대기' : `비우면 현재가 ${formatDecimal(currentPrice)}`
            }
            value={price}
            disabled={isInputLocked}
            onChange={(e) => {
              setPrice(e.target.value)
              onInputChanged()
            }}
          />
        </label>
        <label className="field">
          <span className="field-label">수량 (주)</span>
          <input
            inputMode="numeric"
            value={quantity}
            disabled={isInputLocked}
            onChange={(e) => {
              setQuantity(e.target.value)
              onInputChanged()
            }}
          />
          {ticket !== null && side === 'SELL' && (
            <span className="field-hint">매도 가능 {formatDecimal(ticket.sellableQuantity)}주</span>
          )}
          {ticket !== null && side === 'BUY' && (
            <span className="field-hint">
              매수 가능 {formatMoney(ticket.buyingPower.amount, currency)}
            </span>
          )}
        </label>
        <label className="field">
          <span className="field-label">유효 조건</span>
          <select
            value={timeInForce}
            disabled={isInputLocked}
            onChange={(e) => {
              setTimeInForce(e.target.value as TimeInForce)
              onInputChanged()
            }}
          >
            {TIME_IN_FORCE_OPTIONS[symbol.market].map((option) => (
              <option key={option} value={option}>
                {TIME_IN_FORCE_LABEL[option]} ({option})
              </option>
            ))}
          </select>
        </label>
        {suggestions.length > 0 && (
          <div className="suggestions" aria-label="호가 단위 제안">
            {suggestions.map((amount) => (
              <button
                key={amount}
                type="button"
                className="chip"
                disabled={isInputLocked}
                onClick={() => {
                  setPrice(amount)
                  onInputChanged()
                }}
              >
                {formatDecimal(amount)}
              </button>
            ))}
          </div>
        )}
      </div>

      {error !== null && (
        <p className="warn" role="alert">
          {error.text}
        </p>
      )}
      {result !== null && (
        <p className="order-result" role="status">
          {STATE_LABEL[result.state]}
          {result.brokerOrderId !== null && ` · 주문번호 ${result.brokerOrderId}`}
        </p>
      )}

      {unresolved !== null && result === null && (
        <div className="actions">
          <button type="button" disabled={busy} onClick={() => void place(unresolved)}>
            같은 주문으로 결과 확인
          </button>
        </div>
      )}

      {confirmation !== null && result === null && unresolved === null && (
        <div className="confirm-box" role="alertdialog" aria-label="주문 확인">
          <p>
            {label} {formatDecimal(confirmation.request.quantity ?? '')}주 ·{' '}
            {formatMoney(confirmation.request.limitPrice?.amount ?? '', currency)} ·{' '}
            {TIME_IN_FORCE_LABEL[confirmation.request.timeInForce ?? 'DAY']}
          </p>
          {confirmation.notes.length > 0 && (
            <ul className="confirm-notes">
              {confirmation.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          )}
          <div className="actions">
            <button type="button" disabled={busy} onClick={confirmAndSend}>
              {confirmation.notes.length > 0 ? '확인하고 주문' : `${label} 주문`}
            </button>
            <button type="button" disabled={busy} onClick={() => setConfirmation(null)}>
              돌아가기
            </button>
          </div>
        </div>
      )}

      {confirmation === null && result === null && unresolved === null && (
        <div className="actions order-actions">
          <button
            type="button"
            className={side === 'BUY' ? 'buy' : 'sell'}
            disabled={isSubmitLocked}
            onClick={submitLimit}
          >
            지정가 {label}
          </button>
          <button
            type="button"
            className="outline"
            disabled={isSubmitLocked || !live.isFresh}
            onClick={submitNow}
          >
            현재가 즉시 {label}
          </button>
        </div>
      )}
      {result !== null && (
        <div className="actions">
          <button type="button" onClick={onClose}>
            닫기
          </button>
        </div>
      )}
      <p className="small">
        즉시 주문은 시장가가 아니라 이 순간의 현재가 지정가로 나갑니다. 체결되지 않으면 미체결로
        남습니다.
      </p>
    </div>
  )
}
