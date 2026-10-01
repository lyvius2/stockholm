# 주문 관리 (F14) — 체결 현황과 미체결 정정·취소 설계

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-24 / 상태: **[확정 2026-09-24]** — 탭·모달 구성, 자동 주문 정정 규칙, clientOrderId, 정정 가능 수량은 사용자 결정(8장). 토스 규격은 [확인함 2026-09-24, OpenAPI v1.2.17] / 관련: [`PROJECT.md`](../PROJECT.md) 9장 F1·F14·11.3, [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md) 4·9·10장, [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 1.1, 화면 설계 아티팩트 v18 "주문 관리"

## 1. 결론 요약

- 새 화면을 만들지 않는다. **3번 영역(매수·매도) 오른쪽 표를 세 탭**(보유 n · 미체결 n · 오늘 체결 n)으로 나누고, 미체결 행에 **정정·취소 버튼**을 둔다. 전체 기간 조회는 "그 밖의 화면"의 **주문 내역**이 맡는다.
- 정정은 주문 모달과 같은 틀의 **정정 모달**에서 한다. **지정가만**(토스 규격은 시장가 정정도 허용하지만 F1 원칙으로 막음), 국내는 가격·수량, 미국은 가격만(토스 규격), **항상 확인 창**. 버튼은 셋: **정정 주문**(입력한 가격·수량) · **현재가로 정정**(그 순간의 현재가 지정가로 가격만 바꾸고 수량은 그대로 — 즉시 주문과 같은 원칙, 체결 안 되면 미체결로 남음) · **주문 취소**. 취소는 확인 창 한 번.
- **자동 주문(출처 `AUTO_BUY`·`AUTO_SELL`) 미체결도 사람이 정정할 수 있다.** 사람의 정정은 트리거가 사람(`ManualAmendTrigger`)이라 수동 주문으로 취급되고 한도 규칙의 대상이 아니다. 다만 lot 출처는 자동으로 남아 노출액에 계속 계산되며, 정정 결과가 자동 매수 한도를 넘으면 **확인 창을 한 번 더** 거친다. 자동매매 한도의 유일한 예외이고, 우회 플래그가 아니라 건별 명시적 승인이다(PROJECT 10.1).
- **정정 가능 수량은 잔량까지.** 100주 중 24주 체결이면 최대 76주. 화면은 항상 잔량으로 입력받고, API가 기대하는 값(새 잔량 / 새 총 주문 수량)으로의 변환은 어댑터가 한다.
- **정정 주문의 `clientOrderId`는 새로 발급**(ULID)하고 원주문과 체인으로 잇는다.
- 상태는 토스 실시간 주문 채널(`personal:order`)로 스스로 바뀐다. 정정·취소는 **새 주문번호를 발급**하므로 로컬 주문 기록은 원주문→새 주문 체인을 가진다.
- 결과를 모르는 정정·취소(타임아웃)는 조회로 확인하기 전에 재시도하지 않는다(CLAUDE.md fail-safe).

## 2. 토스 규격 확인 결과 [확인함 2026-09-24]

| 항목 | 내용 |
|---|---|
| 정정 | `POST /api/v1/orders/{orderId}/modify` — 본문 `orderType`(LIMIT/MARKET), `price`, `quantity`, `confirmHighValueOrder`. **KR: `quantity` 필수(양의 정수), US: `quantity` 제공 불가**(`400 us-modify-quantity-not-supported`). 응답 `OrderOperationResponse.orderId` = **새로 발급된 주문 식별자**(원주문과 다름). 오류: 400 잘못된 요청(호가 단위 등, `tickSize`·`nearestPrices` 동봉), 404 주문·계좌 없음, **409 정정 불가 상태**, 422 규칙 위반, 429 |
| 취소 | `POST /api/v1/orders/{orderId}/cancel` — 본문 없음. 이미 체결된 주문은 불가. 응답도 새 `orderId`. **409 취소 불가 상태** |
| 목록 | `GET /api/v1/orders?status=OPEN|CLOSED&symbol&from&to&cursor&limit`. `OPEN`은 전량 반환(커서 무시), `CLOSED`는 커서·`limit`(기본 20, 최대 100). `orderedAt` KST 기준. **Open API로 낼 수 없는 호가 유형(장전·장후 시간외 등)의 주문은 목록·상세 모두에 안 나온다** |
| 상세 | `GET /api/v1/orders/{orderId}` → `Order`: `orderId, symbol, side, orderType, timeInForce, status, price, quantity, orderAmount, currency, orderedAt, canceledAt, execution{filledQuantity, averageFilledPrice, filledAmount, commission, tax, filledAt, settlementDate}` |
| 상태 | `PENDING, PARTIAL_FILLED, PENDING_CANCEL, PENDING_REPLACE`(OPEN 그룹) / `FILLED, CANCELED, REJECTED, REPLACED, CANCEL_REJECTED, REPLACE_REJECTED`(CLOSED 그룹). `CANCEL_REJECTED`·`REPLACE_REJECTED`는 **별도 주문 레코드로 생성**되고 원주문은 이전 상태로 복귀. 클라이언트는 미지의 코드를 허용해야 함 |
| 체결 | 건별 체결 미제공. 주문 1건에 `execution` 하나(평균가·총액·수수료·세금) |
| 실시간 | WebSocket `personal:order`, `codes`에 `accountSeq`(문자열). 본인 종합매매·활성 계좌만. `data.order`는 상세 응답과 같은 모양(`execution.filledAt` 제외). **세션 안 무손실, 끊긴 구간은 재전달 없음 → 재연결 후 `GET /orders?status=OPEN`으로 재동기**. 수신이 2초 이상 막히면 서버가 끊음 |
| 호출 그룹 | 정정·취소 `ORDER`(10/초), 목록 `ORDER_HISTORY`(5/초), 상세 `ORDER_INFO`(6/초, 09:00~09:10은 3) |

**[확인 필요]** (a) 부분 체결 뒤 정정의 `quantity`가 잔량 기준인지 총량 기준인지(규격은 "변경할 수량"). (b) 정정·취소로 생긴 새 주문에 원주문의 `clientOrderId`가 이어지는지, 아니면 null인지. (c) 정정 요청이 원주문 `orderType`을 바꿀 수 있어도 우리는 LIMIT만 쓴다.

## 3. 화면 (상세는 화면 설계 아티팩트 "주문 관리")

### 3.1 3번 영역 세 탭

| 탭 | 행 | 비고 |
|---|---|---|
| 보유 n (기본) | 종목 · 수량 · 평단 · 손익 · 출처 칩 | 지금 표 그대로 |
| 미체결 n | 종목(출처 칩) · 구분 · 가격 · **체결/잔량** · 상태 칩 · 시각 · 정정/취소 버튼 | 정정 가능 수량은 잔량까지. 처리 중 행은 둘 다 잠김 |
| 오늘 체결 n | 종목 · 구분 · 체결가(평균) · 수량 · 금액 · 시각 · 상태(체결 / 취소·부분 체결 / 거부) | `CLOSED` 중 오늘 것. 토스가 건별 체결을 안 주므로 주문 1건 1행 |

- 탭 이름의 숫자는 실시간 건수. 요약 줄의 "미체결 n건"을 눌러도 미체결 탭이 열린다.
- 탭 상태는 저장하지 않는다(종목 변경·재시작 시 보유 탭). 미체결이 새로 생기면 탭 이름만 갱신하고 강제로 바꾸지 않는다.

### 3.2 정정 모달

- 틀: 주문 모달과 같음(바깥 흐림 없음, **원주문 방향 색**의 발광 테두리, 제목 바 드래그, 실행 중 위치 유지). 제목 "정정 · 종목명".
- 구성: 원주문 카드(방향·지정가·수량, 체결/잔량, 상태, 시각, 주문번호 끝 4자리) → 실시간 현재가 → 새 지정가(호가 단위 표시, 400 응답의 `nearestPrices`를 칩으로 제안) · 새 수량(**미국은 잠금 + 사유**) → 가드레일 줄 → 버튼 둘(**정정 주문** = 확인 창 후 / **주문 취소** = 확인 창 후).
- 가드레일: 수동 주문 규칙 집합(`SnapshotFreshness`, `TradingWindow`, `DuplicateIntent` 경고)을 정정 값으로 다시 평가. 원주문이 자동 주문이면 `AutoExposureOverCapNotice`가 정정 후 노출액을 한도와 비교해 넘으면 가드레일 줄을 경고로 바꾸고 확인 창 문구에 "정정 후 노출 X / 한도 Y, 넘는 동안 새 자동 매수 차단"을 쓴다. 1억원 이상은 확인 창에 금액 확인 체크(`confirmHighValueOrder`), 원격 모드·고액·자동 주문 정정은 TOTP.
- "현재가로 정정": 누르는 순간 화면의 현재가(실시간 스트림 값)를 새 지정가에 넣고 확인 창으로 간다. 확인 창에는 그 가격을 고정해 보여 주고, 제출 시점의 현재가가 아니라 **확인 창에 표시된 가격**으로 보낸다(사용자가 본 값과 다른 값이 나가지 않게). 호가 단위에 맞춰 반올림(`RoundingMode` 명시).
- 없음: 시장가 전환, 수량만 늘리는 정정에서의 예수금 초과(가드레일이 아니라 토스 422로 막히지만 화면에서 매수 가능 금액을 먼저 보여 준다).

### 3.3 상태 칩 (토스 10 → 화면 6)

| 토스 | 칩 | 버튼 |
|---|---|---|
| PENDING | 체결 대기 | 정정·취소(자동 주문 정정은 한도 초과 시 확인 창 추가) |
| PARTIAL_FILLED | 부분 체결 (체결/잔량) | 잔량에 대해 정정·취소 |
| PENDING_CANCEL, PENDING_REPLACE | 처리 중 | 잠김 |
| FILLED | 체결 | 없음 |
| CANCELED | 취소 (부분 체결이면 "취소 · 부분 체결") | 없음 |
| REJECTED, CANCEL_REJECTED, REPLACE_REJECTED | 거부 (사유) | 원주문이 복귀한 상태를 다시 표시 |
| REPLACED | 정정됨 → 새 주문 연결 | 새 주문 행에서 |

### 3.4 실시간·알림

- `personal:order` 구독은 lease 보유 디바이스가 우선(PROJECT 8.1). 단독 모드는 그냥 구독.
- 체결(`FILLED`, `PARTIAL_FILLED` 증가) 시 오른쪽 아래 토스트 "체결 · 삼성전자 10주 @74,200" + 알림 센터 항목 + **Slack DM. 기본값은 앱 안 알림·Slack 모두 켬 [확정 2026-09-25]**(사용자 설정으로 끌 수 있음). 단독 모드에서는 앱(데몬)이 켜져 있을 때 클라이언트가 직접 Slack을 보내고, relay 서버가 생기면 앱을 꺼 둔 채로도 서버 경유로 온다. 거부도 알림.
- 연결이 끊기면 3번 영역에 "지연" 칩. 재연결 후 `OPEN` 목록을 다시 받아 로컬과 맞춘다(사라진 주문은 상세 조회로 최종 상태 확정).
- 구현: 토스 키가 쓸 만한 활성 사용자마다 본인 연결에 `personal:order` 를 구독하고(1분마다 맞춤), 주문 채널 구독이 **승인되어 스트림이 새로 살아날 때마다**(데몬 기동 뒤 첫 연결 포함) 미체결을 재동기한다. 구독만 바꿔 다시 승인된 것은 끊긴 구간이 없어 하지 않는다. 재동기는 미체결 → **종료 주문**(마지막으로 끝까지 읽은 날부터, 처음이면 최근 30일 — 끊긴 사이 토스 앱에서 내고 다 체결된 주문도 잡힘) → 그래도 로컬에만 열린 주문의 상세 순서다. 재동기 실패와 **실시간 이벤트 반영 실패**는 재동기 대기열에 올려 1분마다 다시 한다(피드 실행기는 수신자 예외를 로그만 남기고 넘어가므로). 재동기 스냅샷이 그 사이 들어온 이벤트보다 오래됐으면 버리고, 같은 주문을 동시에 고치면 행 버전 충돌로 다시 읽어 판정한다.

### 3.5 주문 내역 화면 (그 밖의 화면) → F20 거래내역 패널의 체결내역 탭으로 흡수 [2026-09-25]

> 별도 화면은 두지 않는다. 아래 규칙은 [TRADE_HISTORY_DESIGN.md](TRADE_HISTORY_DESIGN.md) 3.2 체결내역 탭에 그대로 적용된다.

- 기간·종목·상태 필터. `CLOSED` 커서 페이지(100건)를 로컬 캐시 표에 쌓아 보여 준다(토스 조회 범위 밖의 시간외 주문은 안 보이므로 "Open API로 낸 주문만" 안내).
- 정정 체인(원주문 → 정정 1 → 정정 2)은 한 묶음으로 접어 보여 준다.
- F2 손익의 체결 원천이 이 캐시다.

## 4. 도메인·포트

```java
// core.trading
public enum OrderStatus { PENDING, PARTIALLY_FILLED, PENDING_CANCEL, PENDING_AMEND, FILLED, CANCELLED, REJECTED,
                          CANCEL_REJECTED, AMEND_REJECTED, REPLACED, UNKNOWN }
  // UNKNOWN = 결과를 모름(타임아웃). 재시도 전 반드시 lookup. 토스의 미지 코드도 UNKNOWN으로 받고 경고 이벤트.

/** 정정 요청. 지정가만. 미국은 수량을 바꿀 수 없다(토스). */
public record OrderAmendment(Optional<Money> newLimitPrice, Optional<Quantity> newQuantity) {
  public OrderAmendment { 둘 다 empty이면 InvalidValue; }
  public static OrderAmendment forMarket(Market m, ...)   // US이고 newQuantity가 있으면 InvalidValue
}

public record BrokerOrder(ClientOrderId clientOrderId, String brokerOrderId, Optional<String> replacesBrokerOrderId,
                          OrderIntent intent, OrderStatus status, Quantity filledQuantity,
                          Optional<Money> averageFilledPrice, Instant updatedAt) {
  public Quantity remaining()    // intent.quantity − filledQuantity
  public boolean isOpen()        // PENDING, PARTIALLY_FILLED, PENDING_CANCEL, PENDING_AMEND
  public boolean canAmend()      // isOpen && status ∉ {PENDING_CANCEL, PENDING_AMEND}. 출처 무관(자동 주문도 사람이 정정 가능)
  public boolean canCancel()     // isOpen && status ∉ {PENDING_CANCEL, PENDING_AMEND}
}

// core.port
public interface TradingPort {
  BrokerOrder submit(OrderIntent intent, ClientOrderId id);
  BrokerOrder amend(UserId u, String brokerOrderId, OrderAmendment amendment);   // 새 brokerOrderId를 가진 BrokerOrder 반환. 타임아웃 → OrderResultUnknown
  BrokerOrder cancel(UserId u, String brokerOrderId);                            // 취소 요청으로 발급된 새 주문 레코드 반환
  BrokerOrder lookup(UserId u, String brokerOrderId);
  List<BrokerOrder> openOrders(UserId u, Market m);
  List<BrokerOrder> closedOrders(UserId u, Market m, LocalDate from, LocalDate to, Optional<String> cursor);
  List<Fill> fills(UserId u, Market m, LocalDate day);
  PortfolioSnapshot snapshot(UserId u, Market m);
}
```

- `canAmend`/`canCancel`은 **순수 판정**이고 화면 버튼 활성과 `localapi` 검증이 같은 함수를 쓴다. 정정 수량 상한(`remaining()`)도 여기서 검증한다.
- 사람의 정정은 `OrderIntent.trigger = ManualAmendTrigger(device, 원주문 id)`, `origin`은 원주문 값을 유지한다. 가드레일은 트리거가 사람이므로 한도 규칙(`TotalExposureCap` 등)의 `appliesTo`가 false이고, 대신 `AutoExposureOverCapNotice`가 `Passed`에 "확인 필요" 노트를 붙인다. `localapi`는 그 노트가 있으면 확인 토큰 없이는 제출하지 않는다. 노출액 계산(`AutoBuyExposure`)은 출처 기준이라 정정 후 수량·가격이 그대로 반영된다.
- 정정 주문의 `clientOrderId`는 ULID로 새로 만든다. `DuplicateIntent`는 `replacesBrokerOrderId` 체인을 한 건으로 본다(정정 처리 중 원주문과 새 주문이 함께 OPEN).
- 이벤트(`DomainEvent`): `OrderAmendRequested(userId, brokerOrderId, amendment)`, `OrderCancelRequested(userId, brokerOrderId)` 추가. 결과는 기존 `OrderStatusChanged`·`OrderFilled`·`OrderResultUnknown`으로 흐른다. 감사 로그는 이 연쇄 자체다.
- 어댑터(`engine.broker.toss`): `modify`/`cancel` 응답의 새 `orderId`를 `replacesBrokerOrderId`로 잇고, 원주문은 `REPLACED`(정정) 또는 `CANCELED`로 닫는다. 400의 `tickSize`·`nearestPrices`는 `OrderRejected`에 실어 화면 칩으로 보인다. 타임아웃은 `OrderResultUnknown` → 호출자는 `lookup` 후 결정.
- 로컬 표 `broker_order`(engine 캐시): `broker_order_id`(PK), `client_order_id`, `replaces_broker_order_id`, `user_id`, `symbol`, `side`, `price`, `quantity`, `status`, `filled_quantity`, `avg_price`, `ordered_at`, `updated_at`, `origin`. 체인 조회는 `replaces_broker_order_id`를 따라간다.

## 5. 흐름

```
미체결 행 [정정] → canAmend? → 정정 모달(수량 ≤ 잔량) → 가드레일(수동 규칙 + 자동 주문이면 AutoExposureOverCapNotice) → 확인 창(한도 초과 승인 / 1억 이상 체크 / 원격·자동 주문 TOTP)
  → OrderAmendRequested → TradingPort.amend → 새 BrokerOrder(PENDING_AMEND → personal:order로 PENDING/REPLACED)
  → 타임아웃이면 OrderResultUnknown → 행 "확인 중" 잠금 → lookup(원주문) → 상태 확정 후에만 재시도 허용
미체결 행 [취소] → canCancel? → 확인 창 → OrderCancelRequested → TradingPort.cancel → PENDING_CANCEL → CANCELED / CANCEL_REJECTED(원주문 복귀)
```

- 정정 도중 체결이 끝나 409가 오면 `OrderRejected("이미 체결됨")`로 화면에 알리고 `OPEN` 목록을 다시 받는다.
- 취소 요청이 `CANCEL_REJECTED`로 돌아오면 별도 레코드가 생기므로, 원주문 행은 그대로 두고 거부 사유만 토스트로 보인다.

## 6. 예외와 fail-safe

| 상황 | 동작 |
|---|---|
| 새 주문 타임아웃 | `OrderResultUnknown` 기록 후 "확인 중". 토스 주문 조회에는 멱등 키가 없고, 같은 키로 다시 보내면 첫 요청이 닿지 않았을 때 가드레일 없이 새 주문이 생기므로 **다시 보내지 않는다.** 5초마다 미체결·오늘 종료 목록을 **읽기만 해서** 속성(종목·방향·유형·가격·수량·유효 조건, 보낸 시각 − 10초 이후 접수, 다른 요청에 연결되지 않은 주문)으로 찾는다. 한 건이면 연결, 여러 건이면 사람 확인, 5분 안에 못 찾으면 사람 확인 [제안]. 요청은 보내기 전에 `order_submission`(SENDING)에 남기므로 접수 뒤 기록 실패·데몬 재시작에도 확인이 이어진다(SENDING 은 30초 뒤부터 확인). 멱등 키는 화면이 제출 때 만들어 주며 같은 키의 두 번째 요청은 첫 결과를 돌려받는다 |
| 정정 타임아웃 | 요청을 `order_submission`(원주문 번호 포함)에 먼저 남기고 다시 보내지 않음. 토스 주문 응답에는 원주문 연결 정보가 없어 **원주문 상태로만 판정**: 30초 뒤 원주문이 그대로 열려 있으면 "반영 안 됨"(거부되면 원주문이 이전 상태로 돌아옴), 정정 처리 중이면 기다림, `REPLACED` 면 반영됐지만 새 주문 번호는 속성으로 고르지 않고 사람 확인(같은 모양의 앱 주문과 구별 불가), 이미 체결·취소면 "반영 안 됨". 같은 요청 키에 다른 정정 내용은 거부 |
| 취소 타임아웃 | "확인 중". 원주문 상태는 실시간 주문 채널·재동기가 알려 줌. 토스는 이미 취소·정정된 주문의 취소를 409 로 거부하므로 다시 눌러도 주문이 생기지 않아 따로 확인하지 않음 |
| 409 정정·취소 불가 | "이미 체결됨/취소됨" 안내, 목록 재동기 |
| 400 호가 단위 | `nearestPrices` 칩 제안, 재입력 |
| 422 규칙 위반(예수금·수량) | 토스 메시지 그대로 표시 |
| 자동 주문 정정에서 한도 초과 | `AutoExposureOverCapNotice` 노트 → 확인 토큰 없이는 `localapi`가 제출 거절. 승인은 감사 로그(`OrderAmendRequested`에 `overCapApproved=true`) |
| 정정 수량 > 잔량 | `InvalidValue`. 화면은 잔량을 상한으로 입력 제한 |
| WebSocket 끊김 | "지연" 칩, REST 폴링(ORDER_HISTORY 그룹 한도 안에서 10초), 재연결 시 재동기 |
| 정정으로 수량 증가 | 매수 가능 금액을 먼저 보여 주고, 초과는 토스 422로 막힘. 자동 주문이 아니므로 한도 가드레일은 걸리지 않음(F1 원칙) |

## 7. 테스트

- `OrderAmendment`: 둘 다 비면 `InvalidValue`, US + 수량 → `InvalidValue`, KR 가격만·수량만·둘 다 허용.
- `BrokerOrder.canAmend/canCancel`: 상태 10종 표 전체(출처 무관). `PENDING_CANCEL`·`PENDING_AMEND`는 둘 다 false. 정정 수량 > `remaining()` → `InvalidValue`(24주 체결 후 77주는 거부, 76주는 허용).
- `AutoExposureOverCapNotice`: 정정 후 노출 = 정확히 한도 → 노트 없음, 한도 + 1원 → 노트. 수동 출처 주문에는 적용 안 됨. `TotalExposureCap`은 `ManualAmendTrigger`에 `appliesTo` false.
- 상태 매핑 학습 테스트: 토스 10개 코드 + 미지 코드 → `UNKNOWN`.
- 어댑터(WireMock): modify 200(새 orderId), 400 `nearestPrices`, 409, 422, 타임아웃 → `OrderResultUnknown`; cancel 200/409; `OPEN` 전량 / `CLOSED` 커서 페이지; `personal:order` 재연결 재동기.
- 화면 접근 규칙: 자동 주문 행의 정정 요청이 `localapi`에서 거절되는지.

## 8. 열린 항목

**결정됨 (2026-09-24, 사용자)**
1. 3번 영역 세 탭과 정정 모달 구성 승인.
2. 자동 주문 미체결도 사람이 정정 가능. 사람의 정정은 수동 주문 취급, lot 출처는 자동 유지, 한도 초과 시 확인 창 한 번(자동매매 한도의 유일한 예외).
3. 정정 주문의 `clientOrderId`는 새로 발급(ULID), 원주문과 체인.
4. 정정 가능 수량은 잔량까지(100주 중 24주 체결이면 최대 76주).
5. 정정은 항상 확인 창. "현재가로 정정" 버튼 추가(2026-09-24).

**남은 사용자 결정**
1. ~~체결 알림 기본값~~ → 앱 안 + Slack 모두 켬으로 결정(2026-09-25).

**구현 메모**: 원주문 상태는 로컬 기록이 아니라 토스 상세 조회로 그 자리에서 판단한다. 국내 정정은 가격만 바꿀 때도 수량을 보내야 해서 **잔량**을 보낸다(잔량 전체를 새 가격으로). 토스 규격의 "변경할 수량"은 국내 관행상 "잔량 중 정정할 수량"이며, 우리는 잔량보다 많이 보내지 않으므로(검증) 토스가 어떻게 해석하든 의도보다 많이 사거나 팔지 않는다 — 가능성이 낮은 "새 전체 수량" 해석에서만 의도보다 적게 남는다(아래 외부 확인 1). 가드레일은 정정 대상 원주문을 같은 방향 미체결로 세지 않는다(체인 한 건). 한도 초과 확인 창(`AutoExposureOverCapNotice`)은 6단계에서 붙인다.

**외부 확인**
1. 정정 API의 `quantity`가 "잔량 중 정정할 수량"(국내 관행, 우리 해석)인지 "새 총 주문 수량"(예: 24 체결 + 50 = 74)인지. 우리는 잔량 이하만 보내므로 어느 쪽이든 초과 매매는 없고, 후자라면 의도보다 적게 남는다. 잔량보다 적은 수량을 넣었을 때 나머지가 옛 가격으로 남는지(일부 정정)도 함께 확인한다. 규격은 "변경할 수량"으로만 적혀 있다. 화면은 잔량으로 입력받고 어댑터가 변환하므로 어느 쪽이든 화면은 같다. 확인은 사용자가 지정한 소액 실주문 또는 토스 개발자센터 문의.
2. `personal:order` 재연결 시 `PENDING_CANCEL` 중이던 주문의 최종 상태가 상세 조회로 확정되는지.

## 9. 조건주문 (F1 조건 주문 탭)

조건주문은 토스 서버가 감시하고 발동하므로 Stockholm 은 **등록·수정·취소·조회**만 한다. 목록은 토스가 원본이라 저장하지 않고 그 자리에서 읽는다(토스 앱에서 등록한 조건주문도 보인다).

| 항목 | 결정 |
|---|---|
| 상태 | SINGLE 추가·지정가만·등록 순간 가드레일·발동 주문 수동 출처 [확정](PROJECT F1, 사용자 승인) |
| 타입 | `SINGLE`·`OCO`(두 조건 매도, 첫 감시가 > 둘째 감시가)·`OTO`(첫 조건 매수, 둘째 매도). 형태는 `ConditionalOrderIntent` 가 생성 시 검사 |
| 호가 유형 | **지정가만**. 토스는 SINGLE 시장가를 받지만 F1 시장가 예외(미국 소수점 매도·금액 매수)에 해당하지 않아 만들지 않음 |
| 수량 | 1주 이상 정수 주수, 조건 둘이 같은 수량 |
| 가드레일 | 등록·수정 순간에만: `ConditionalExpiry`(만료일이 오늘 이전이면 거부, 오늘은 허용), `ConditionalHighValue`(조건마다 주문 가격 × 수량 중 큰 쪽을 원화 환산해 1억 이상 확인·30억 초과 거부, 규칙 이름은 수동 주문과 같은 `HighValueOrder` 라 확인하면 `confirmHighValueOrder`). 계좌·장 상태는 발동 시점에 달라지므로 보지 않음 |
| 발동된 주문 | 토스가 일반 주문으로 냄 → 실시간 주문 채널·재동기로 `broker_order` 에 들어오고 Stockholm 이 낸 주문이 아니라 수동(외부) 출처로 lot 반영 |
| 요청 기록 | `conditional_submission`(V2.10). 등록은 멱등 키를 토스로 보내고, 수정은 토스에 멱등 키가 없어 로컬 키로 이중 클릭만 막음. 같은 키에 다른 내용은 거부 |
| 결과 모름 | 다시 보내지 않음. 등록·수정 모두 요청 상태와 무관하게 보낸 뒤 30초를 기다린다(정확히 30초부터 확인). 등록은 그 종목의 OPEN·CLOSED 목록에서 속성(타입·수량·만료일·감시가·주문 가격, 등록 시각 ≥ 보낸 시각 − 10초, 이미 연결된 번호 제외)으로 한 건을 찾으면 확정, 여러 건이면 사람 확인, **SINGLE 은 같은 모양이 있어도 확정하지 않고 사람 확인**(조회에 매매 방향이 없어 앱에서 등록한 반대 방향 SINGLE 과 구별 불가), 목록이 5쪽을 넘으면 판정 보류, 5분 안에 못 찾으면 사람 확인. 수정은 기존 조건주문이 그대로 열려 있으면 "반영 안 됨", 사라졌으면 새 번호를 고르지 않고 사람 확인 |
| 취소 | 가드레일 없음(위험을 줄이는 조작). 결과를 모르면 확인 중으로 돌려주고 목록으로 확인 |
| 호출 한도 | 등록·수정·취소 `CONDITIONAL_ORDER` 초당 5, 조회 `CONDITIONAL_ORDER_HISTORY` 초당 10. 서킷은 일반 주문과 같은 토스 주문 경로 |

**남은 확인**: 토스 응답의 감시 조건에는 **매매 방향이 없어** 토스 앱에서 등록한 SINGLE 은 방향을 모른다(우리가 등록한 것은 요청 기록으로 안다). 수정 뒤 옛 번호 상세 조회가 404 인지 종료 상태인지는 문서에 없다(둘 다 "사라짐"으로 처리). 조건주문 등록 가능 시간대와 만료일 상한도 문서에 없다(토스 거부에 맡김).

## Changes

| 날짜 | 변경 |
|---|---|
| 2026-09-29 | 4장 포트: 정정은 `placeAmendment(OrderAmendRequest)`(국내 가격+수량, 미국 가격만을 요청 타입이 검증), 취소는 `cancelOrder`, 둘 다 새 주문 번호의 `OrderReceipt` 를 돌려줌. 상태는 `lookupOrder` 의 `BrokerOrderRecord` 로 확인(CORE_DOMAIN Changes 와 같음) |
| 2026-09-29 | 6장: 새 주문 타임아웃은 다시 보내지 않고 주문 목록을 읽어 속성으로 확인, 요청은 `order_submission` 에 먼저 기록, 멱등 키는 화면이 줌 [제안] |
| 2026-09-29 | 3.4: 실시간 반영 구현 규칙(구독 대상, 재동기 시점, 뒤처진 스냅샷 버림) |
| 2026-09-29 | 6장 정정·취소 타임아웃을 나눠 적음, 8장 구현 메모(원주문은 토스에서 확인, 국내 가격 정정은 잔량 전송, 원주문은 같은 방향 미체결에서 제외) |
| 2026-09-29 | 3.4: 재동기에 종료 주문 포함(30일·이어 읽기), 이벤트 반영 실패도 재동기로 복구, 행 버전으로 동시 갱신 방지 |
| 2026-09-29 | 정정 결과 모름은 원주문 상태로만 판정(새 주문 번호는 사람 확인), 국내 정정 수량은 잔량 전송과 그 근거(잔량 이하라 초과 매매 없음), 같은 요청 키 다른 내용 거부 |
| 2026-09-30 | 9장 조건주문: 지정가만, 등록·수정 순간 가드레일, `conditional_submission`, 결과 모름 확인 방식 |
| 2026-09-30 | 9장: SINGLE 결과 모름은 속성으로 확정하지 않음, 유예 30초는 상태와 무관, 조건주문 결정은 [제안] 표시 |
| 2026-10-01 | 9장 조건주문 결정을 [확정]으로 |
