# 調査と判断

- **Decision**: frame処理はpointer handler外のCompose scopeに置く。**Rationale**: [AwaitPointerEventScope](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/AwaitPointerEventScope)はrestricted suspensionで、一般のframe待ちを混ぜられない。**Alternatives**: pointer MOVEイベントだけでは静止した指の保持中にpanが続かない。独自timer/gesture engineは不要。
- **Decision**: [MonotonicFrameClock](https://developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock)の時刻差とlive owner guardを使う。**Rationale**: display更新へ同期し、旧ownerのqueued frameを拒否できる。**Alternatives**: recomposition後のjob cancelだけでは同期取消の保証がない。
- **Decision**: deltaは現在viewportでpointerをworldへ逆変換し、DOWN world anchorとの差だけを使う。**Rationale**: `(end-start)/scale`にpan分を足す方式よりpreview/commitのauthorityが一つになる。**Alternatives**: cameraとelementを独立累積して二重加算する方式は採用しない。
- **Decision**: frameはpreviewのみ、releaseで既存moveSelection/saveを一回実行。**Rationale**: region/multi/arrow/inkの包含とUndo/保存を既存authorityへ残す。schema変更なし。
- **Decision**: band/速度の2候補をplanのbounded prototypeで比較し、実装設定として採用値を記録する。**Rationale**: Issue78が実機相当の比較を求め、最初から数値を製品仕様に固定しないため。**Alternatives**: 利用者向けの速度settings、独自gesture、常設chromeは追加しない。
- **Decision**: manual Compose test clockでstationary pointerと停止を検証する。**Rationale**: [MainTestClock](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/MainTestClock)のframeを進め、壁時計によるsleepだけに依存しない。**Alternatives**: active ticker中に無条件waitForIdleする検証は避ける。
- 下位モデルのread-only調査を主担当が既存コードと公式資料に照合した。新しいdependencyや製品conceptの判断は不要。採用値のUX比較と#73 merge後のcurrent censusは実装/検証taskで完了する。
