# AutoZone 차량 UI

2026-11-14. Native Android Activity, Operate mode. 공식 Pleos 에뮬레이터의 앱 창(1682×1210)에 맞춰 실제 실행 화면에서 검토한다.

- 기본 색: #002C5F와 흰색. 보조 글자 #44596F, 연한 면 #EEF3F9, 구분선 #C6D2E1. 상태는 색에만 의존하지 않고 ‘실행 중’, ‘대기’, ‘확인 중’으로 표현한다.
- 홈: 현재 루틴 / 네 가지 현재 설정 / 활성 세션 복원 버튼. 프리셋 전체 설명과 개발 로그를 홈에 쌓지 않는다.
- 글자: 상태 제목 54sp, 값 64sp, 주요 버튼 30–38sp, 보조 내용 24–32sp. native sans-serif, 보통/굵게 두 굵기. 크기와 여백은 sp/dp를 구분한다.
- 터치: 기본 버튼 최소 높이 84dp, 주 복원 버튼 116dp. 다른 버튼과 충분한 간격을 유지한다. 1100dp 미만 폭에서는 설정값을 2열로 배치한다.
- 관리: P단이고 CarUx 편집 제한이 없을 때만 접근. 주행 제한 또는 P단 해제 시 홈으로 복귀한다. 클릭 때도 조건을 다시 확인한다. 드라이브스루의 현재 기어·속도 제한 부재는 상세 화면에서 명시한다.
- 진단: SDK 응답 요약·최근 이벤트와 개발 시뮬레이션 조작은 별도 정차 화면에 둔다. unknown을 0이나 꺼짐으로 바꾸지 않는다.
- 화면의 ‘현재 설정’은 표시 상태이며 독립 SDK getter 실증 성공을 의미하지 않는다. 실제 제어 검증은 별도 로그로 확인한다.
- 내비게이션은 같은 Activity 안에서 전환해 엔진을 중복 생성하지 않는다. 페이지/선택 루틴을 렌더 키에 포함하고 같은 페이지의 스크롤·포커스를 보존한다.

참고 기준(앱 배포 승인을 의미하지 않음): [AAOS typography](https://developers.google.com/cars/design/automotive-os/design-system/typography), [AAOS sizing](https://developers.google.com/cars/design/automotive-os/design-system/sizing). 프로젝트의 실제 폼팩터와 에뮬레이터 화면에서 별도로 확인한다.
