# 調査と判断

- **Decision**: frame処理はpointer handler外のCompose scopeに置く。**Rationale**: [AwaitPointerEventScope](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/AwaitPointerEventScope)はrestricted suspensionで、一般のframe待ちを混ぜられない。**Alternatives**: pointer MOVEイベントだけでは静止した指の保持中にpanが続かない。独自timer/gesture engineは不要。
- **Decision**: [MonotonicFrameClock](https://developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock)の時刻差とlive owner guardを使う。**Rationale**: display更新へ同期し、旧ownerのqueued frameを拒否できる。**Alternatives**: recomposition後のjob cancelだけでは同期取消の保証がない。
- **Decision**: deltaは現在viewportでpointerをworldへ逆変換し、DOWN world anchorとの差だけを使う。**Rationale**: `(end-start)/scale`にpan分を足す方式よりpreview/commitのauthorityが一つになる。**Alternatives**: cameraとelementを独立累積して二重加算する方式は採用しない。
- **Decision**: frameはpreviewのみ、releaseで既存moveSelection/saveを一回実行。**Rationale**: region/multi/arrow/inkの包含とUndo/保存を既存authorityへ残す。schema変更なし。
- **Decision**: band/速度の2候補をplanのbounded prototypeで比較し、実装設定として採用値を記録する。**Rationale**: Issue78が実機相当の比較を求め、最初から数値を製品仕様に固定しないため。**Alternatives**: 利用者向けの速度settings、独自gesture、常設chromeは追加しない。
- **Decision**: manual Compose test clockでstationary pointerと停止を検証する。**Rationale**: [MainTestClock](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/MainTestClock)のframeを進め、壁時計によるsleepだけに依存しない。**Alternatives**: active ticker中に無条件waitForIdleする検証は避ける。
- 下位モデルのread-only調査を主担当が既存コードと公式資料に照合した。新しいdependencyや製品conceptの判断は不要。採用値のUX比較と#73 merge後のcurrent censusは実装/検証taskで完了する。

## Issue93: 次のnative panのoracle（2026-10-04）

- **Decision**: 通常panの期待値を、同じdownTimeの一pointer streamでWindowへ届いたDOWNと最後のMOVEの差分から求める。test-only `Window.Callback` delegateはeventを変更せずoriginalへ一回渡し、finallyで元へ戻す。既存normal panはMOVE差分を受理し、UPでは終了するため、UPの座標を新たなcamera差分として期待しない。
- **Rationale**: [AOSP InputConsumer](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/input/InputConsumer.cpp)はMOVEを表示時点へ補間・予測できる。注入位置とWindow配信位置の同一性を前提にした110/35px固定oracleは、正しい配信応答も失敗させる。制御観測では注入(110,35)、配信(165,52.5)、表示(165,53)、同じcanvas Rectを実測した。historyには元の注入座標が残った。
- **Validation**: 同じ有効な高速入力の旧oracleはRED、新oracleはSTOP/resume・再生成ともGREEN。標準Windows GMD、owned/sourceUnchanged true、介入0。許容2pxを維持し、native DOWN/MOVE/UP受理・slopを超える正方向移動・layout・Board/Room/save/Undo/Redo不変とpan終了後30frameの安定を照合する。
- **Limits**: 旧CIのnative座標は記録されておらず、過去二件の具体的な補正量/時刻は復元していない。最初の診断probeはdispatch中の整形が時刻へ影響し得る。Windows・Pixel9とも配信/表示が一致したPASSだけで旧failureを解決扱いしない。制御観測はoracleの前提不足を実証する独立した根拠であり、過去CIのtimestamp再現とは区別する。
- **Scope**: 通常fixtureの時刻/source/tool/標準注入経路は既存のまま。診断用高速timestamp・NativePan93ログ・history配列は除去する。callback内は当該streamの座標/終端の軽い記録だけ。camera/model/schema/依存/CI/launcherと既存取消・古いUP・pinch/移動previewのassertは変更しない。#78のclosed historical preview→commit調査は別identity/段階である。
