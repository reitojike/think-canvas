# 検証と checkpoint

## 修正前の RED

製品コードは baseline `047b0a1060e31fad184248bb24d4bfaa6c646d20` のまま、
`TextEditorDismissalTest#shiftedNativeBlankDoubleTapResolvesBeforeEditorEntry` を Pixel9/API37 GMD で実行。
first UP → platform minimum interval → 8pxずらした second DOWN/UP の native MotionEvent を注入。
timing/slop の前提は通過し、`First tap must not start an editor` で失敗。
fresh XML は exact testcase 1、tests/failures/errors/skipped = **1/1/0/0**。
先行したテスト compile error と first UP だけの失敗は、この pair 全体の RED と区別する。

## 初回 focused の HOLD / completeness checkpoint

family は blank tap arbitration の検証 authority。pending 失効回帰の
`Invalidation must happen within the tap window` が失敗した。
現在の assertion は ActivityScenario の STOP/recreation **処理完了時刻**を計測する。
必要な観測は pending の authority が失効する **ON_STOP / editor 開始 edge**。
完了時刻が遅いことだけでは stale Draft の発生を示さない。

有限な surface:

| surface | 確認と境界 |
| --- | --- |
| central pointer handler | first UP の保留、second DOWN の platform minimum/timeout/slop、non-blank と ink/tool の admission |
| local pending / Job | identity、first world、generation、editor・save・selection・content guard |
| lifecycle / session | ON_STOP と dispose の取消、board/editorSession による ownership |
| editor / focus / IME | confirmed single だけが既存 Draft 入口へ進む。#71 finalization と focus job を再設計しない |
| content / Room / history | pending と double は save/Undo へ進まない。既存 zoom だけが viewport navigation を記録 |
| native regression | first UP と second DOWN の real uptime、slop 内外、選択、失効 edge を観測する |

判定: **BOUNDED_CORRECTION**。失効 edge を直接 timestamp するテスト補正に限定する。
production authority・保存・gesture ownership を広げない。blind rerun はしない。
save/tool/board の追加 regression は初期受け入れ条件の coverage として同じ finite surface に閉じる。
追加の material finding は再度 HOLD/checkpoint する。

初回 focused の最終 XML は 26/2/0/0。もう1件は selected single の timeout 後の即時 semantics 読み取りで、選択解除がまだ観測されない失敗。
同じ timing family の completeness checkpoint に含める。Compose の mainClock と native uptime は別であり、
Thread.sleep だけで coroutine の virtual deadline が進んだとは扱わない。
固定の platform timeout 分だけ mainClock を進め、未失効なら実行される時点を明示して single / stale を確認する。
shifted double、selection double、slop 外の2 single、confirmed new Draft、既存 #71 は初回で成功した。
unit task は GMD failure により未実行であり、成功とは記録しない。
参照: [Compose test synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization)。

## 2回目の timing family checkpoint

単一 selector の session/STOP 回帰は 1/1/0/0。STOP edge 自体も native window 外だった。
ログの ActivityScenario は STOP 用の EmptyActivity 起動を待ち、表示に約10秒を要している。
この呼出しで real uptime 300ms 内の STOP を作れたとは主張しない。

判定: **BOUNDED_CORRECTION**、2回目の範囲は stale pending 回帰の clock authority のみ。
native double の timing/slop 回帰はそのまま維持する。stale 回帰は mainClock を固定し、
first UP 後の固定3 frameだけ処理して Draft 不在を確認する。その clock が platform timeout 未満のまま、
実際の lifecycle/session/tool/save/board change を行い、期限を進めて old action 不在を検証する。
Android の遅い ActivityScenario transition と Compose coroutine deadline を混同しない。
production / dependency / workflow は変更しない。補正後の新しい material finding は再び HOLD/checkpoint。

## 補正後の focused 結果

- selected single: fresh exact XML **1/0/0/0**。
- editor replacement / STOP / recreation: 単一 testcase 内の3ケース、fresh exact XML **1/0/0/0**。
- spatial / ink / save / board switch: 単一 testcase 内の4ケース、fresh exact XML **1/0/0/0**。
- unit: **147/0/0/0**。lint / debug / androidTest build 成功を確認した。
- comma 区切りの3 method 指定は XML が1件だけだったため、3件成功とは採用していない。
  既存の単一 `Class#method` 指定で残る各 testcase を別 invocation として確認した。
- final candidate の source instrumentation census は、重複なしの **160 class#method**。

ここまで production の追加補正は行っていない。final-head full suite / canonical review は別 gate。

## 最終検証

final candidate 固定後に unit/lint/debug/androidTest build と full Pixel9/API37 GMD を行う。
source testcase 集合と fresh XML の class#method 全件、device、counter、head / source 不変を照合する。
current-head canonical review と unresolved thread 0 の証跡がない間は MERGE_READY としない。
