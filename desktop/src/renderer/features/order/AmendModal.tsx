import { useEffect, useRef, useState } from 'react'
import type { ApiOrderAmendment } from '@renderer/generated/api-order-amendment'
import type { ApiOrderListing } from '@renderer/generated/api-order-listing'
import type { ApiOrderPlacement } from '@renderer/generated/api-order-placement'
import { tradingApi } from '@renderer/data/api/trading'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'
import { pendingKey, useOrderUiStore, type PendingAmend } from '@renderer/data/store/orderUi'
import { formatDecimal, formatMoney } from '@renderer/shared/format/decimal'
import { newUlid } from '@renderer/shared/id/ulid'

interface AmendModalProps {
  readonly order: ApiOrderListing
  /** 응답을 못 받았던 정정을 이어서 확인할 때. 같은 멱등 키만 씀. */
  readonly resume?: PendingAmend | undefined
  readonly onClose: () => void
  readonly onChanged: (placement: ApiOrderPlacement) => void
}

/** 확인 창에 고정해 보여 주는 요청. 확인 창에 보인 값 그대로 보냄. */
interface Confirmation {
  readonly request: ApiOrderAmendment
  readonly notes: readonly string[]
}

const SIDE_LABEL = { BUY: '매수', SELL: '매도' } as const
const STATE_LABEL: Record<ApiOrderPlacement['state'], string> = {
  ACCEPTED: '정정 주문 접수됨',
  PENDING: '확인 중 — 결과를 조회하고 있습니다',
  NEEDS_REVIEW: '사람 확인 필요 — 토스에서 주문 여부를 확인하세요',
}

/**
 * 정정 모달(F14).
 * 주문 모달과 같은 틀이며 지정가만, 국내는 가격·수량, 미국은 가격만(토스 규격), 항상 확인 창을 거침.
 * "현재가로 정정" 은 누르는 순간의 현재가를 새 지정가에 넣고 확인 창에 고정해 그 값으로 보냄.
 * 응답을 못 받은 정정은 모달 밖 store 에 남아 탭을 바꾸거나 닫아도 같은 키로만 결과를 확인함.
 */
export function AmendModal({ order, resume, onClose, onChanged }: AmendModalProps) {
  const live = useLiveQuote(order.symbol)
  const currency = order.symbol.market === 'KR' ? 'KRW' : 'USD'
  const isUs = order.symbol.market === 'US'
  const [price, setPrice] = useState(
    resume?.request.newLimitPrice?.amount ?? order.limitPrice?.amount ?? '',
  )
  const [quantity, setQuantity] = useState(resume?.request.newQuantity ?? order.remaining)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ text: string; isLocking: boolean } | null>(null)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [result, setResult] = useState<ApiOrderPlacement | null>(null)
  const [unresolved, setUnresolved] = useState<ApiOrderAmendment | null>(resume?.request ?? null)
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

  function buildRequest(
    newPrice: string,
    newQuantity: string | null,
    confirmedRules: string[] = [],
  ) {
    submissionKey.current ??= newUlid()
    const request: ApiOrderAmendment = {
      clientOrderId: submissionKey.current,
      newLimitPrice: { amount: newPrice, currency },
      confirmedRules,
    }
    if (newQuantity !== null) request.newQuantity = newQuantity
    return request
  }

  function validate(newPrice: string, newQuantity: string | null): string | null {
    if (!/^\d+(\.\d+)?$/.test(newPrice)) return '가격은 숫자여야 합니다'
    if (newQuantity === null) return null
    if (!/^\d+$/.test(newQuantity) || /^0+$/.test(newQuantity))
      return '수량은 1주 이상 정수여야 합니다'
    if (BigInt(newQuantity) > BigInt(order.remaining))
      return `수량은 잔량 ${formatDecimal(order.remaining)}주까지입니다`
    return null
  }

  async function send(request: ApiOrderAmendment) {
    const key = pendingKey('amend', order.brokerOrderId)
    useOrderUiStore.getState().begin({
      kind: 'amend',
      key,
      brokerOrderId: order.brokerOrderId,
      request,
      state: 'sending',
    })
    setBusy(true)
    setError(null)
    setSuggestions([])
    try {
      const placement = await tradingApi(localClient).amend(order.brokerOrderId, request)
      useOrderUiStore.getState().resolve(key)
      submissionKey.current = null
      setUnresolved(null)
      setConfirmation(null)
      setResult(placement)
      onChanged(placement)
    } catch (e) {
      if (!(e instanceof ApiError)) {
        useOrderUiStore.getState().markUnresolved(key)
        setUnresolved(request)
        setConfirmation(null)
        setError({
          text: '응답을 받지 못했습니다. 정정이 접수됐을 수 있으니 같은 요청으로 결과를 확인하세요.',
          isLocking: false,
        })
        return
      }
      // 데몬이 답했으면 정정은 나가지 않은 것이라 결과 모름 상태를 풂
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

  // 정정 주문: 입력한 가격(과 국내는 수량)으로 확인 창
  function submitTyped() {
    const newQuantity = isUs ? null : quantity.trim()
    const problem = validate(price.trim(), newQuantity)
    if (problem !== null) {
      setError({ text: problem, isLocking: false })
      return
    }
    setError(null)
    setConfirmation({ request: buildRequest(price.trim(), newQuantity), notes: [] })
  }

  // 현재가로 정정: 그 순간의 현재가를 고정해 확인 창으로. 값이 지금 것이 아니면 막음
  function submitAtCurrent() {
    if (!live.isFresh || currentPrice === null) {
      setError({
        text: '현재가가 지연돼 현재가 정정을 낼 수 없습니다. 가격을 직접 넣으세요.',
        isLocking: false,
      })
      return
    }
    setPrice(currentPrice)
    setError(null)
    setConfirmation({
      request: buildRequest(currentPrice, isUs ? null : order.remaining),
      notes: [],
    })
  }

  function confirmAndSend() {
    if (confirmation === null) return
    const rules = confirmation.notes.map((note) => note.split(':')[0]?.trim() ?? note)
    void send({ ...confirmation.request, confirmedRules: rules })
  }

  function onInputChanged() {
    setError(null)
    setConfirmation(null)
    if (unresolved === null) submissionKey.current = null
  }

  const label = SIDE_LABEL[order.side]
  return (
    <div className="order-modal" role="dialog" aria-label={`${label} 정정`} data-side={order.side}>
      <header className="order-modal-title">
        <strong>{label} 정정</strong>
        <span className="num">{order.symbol.code}</span>
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

      <div className="original-order" aria-label="원주문">
        <span>{label}</span>
        <span className="num">
          {order.limitPrice ? formatMoney(order.limitPrice.amount, currency) : '—'}
        </span>
        <span className="num">
          체결 {formatDecimal(order.filledQuantity)} / 잔량 {formatDecimal(order.remaining)}
        </span>
        <span className="num">…{order.brokerOrderId.slice(-4)}</span>
      </div>

      <div className="order-form">
        <label className="field">
          <span className="field-label">새 지정가 ({currency})</span>
          <input
            inputMode="decimal"
            value={price}
            disabled={isInputLocked}
            onChange={(e) => {
              setPrice(e.target.value)
              onInputChanged()
            }}
          />
        </label>
        <label className="field">
          <span className="field-label">새 수량 (주)</span>
          <input
            inputMode="numeric"
            value={quantity}
            disabled={isInputLocked || isUs}
            onChange={(e) => {
              setQuantity(e.target.value)
              onInputChanged()
            }}
          />
          <span className="field-hint">
            {isUs
              ? '미국 주문은 가격만 정정할 수 있습니다(토스 규격)'
              : `잔량 ${formatDecimal(order.remaining)}주까지`}
          </span>
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
          {result.brokerOrderId !== null && ` · 새 주문번호 ${result.brokerOrderId}`}
        </p>
      )}

      {unresolved !== null && result === null && (
        <div className="actions">
          <button type="button" disabled={busy} onClick={() => void send(unresolved)}>
            같은 요청으로 결과 확인
          </button>
        </div>
      )}

      {confirmation !== null && result === null && unresolved === null && (
        <div className="confirm-box" role="alertdialog" aria-label="정정 확인">
          <p>
            새 지정가 {formatMoney(confirmation.request.newLimitPrice?.amount ?? '', currency)}
            {confirmation.request.newQuantity !== undefined &&
              ` · 수량 ${formatDecimal(confirmation.request.newQuantity)}주`}
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
              {confirmation.notes.length > 0 ? '확인하고 정정' : '정정 주문'}
            </button>
            <button type="button" disabled={busy} onClick={() => setConfirmation(null)}>
              돌아가기
            </button>
          </div>
        </div>
      )}

      {confirmation === null && result === null && unresolved === null && (
        <div className="actions order-actions">
          <button type="button" disabled={isSubmitLocked} onClick={submitTyped}>
            정정 주문
          </button>
          <button
            type="button"
            className="outline"
            disabled={isSubmitLocked || !live.isFresh}
            onClick={submitAtCurrent}
          >
            현재가로 정정
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
        정정은 지정가만, 시장가로 바꿀 수 없습니다. 토스는 정정에 새 주문번호를 발급합니다.
      </p>
    </div>
  )
}
