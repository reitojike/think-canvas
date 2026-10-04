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

## region editor authority の HOLD / family completeness checkpoint

候補 `7b3ae7b` の full 実行は中断され、完了 XML / exit / source-after がない。
回収した88 testcase の logcat では、既存の
`ConditionalChromeLifecycleTest#regionNameTransitionRemovesFormerSelectionSharePointUntilReselected`
だけに TestRunner failure がある。全件成功の証跡には採用しない。

原因は pending admission / final guard が既存の regionNameDraft まで null 必須にしたこと。
従来、囲み名 editor が成立した状態でも空白 single は選択を解除できた。
「pending 中に editor が変更されたら失効」と「tap 前から安定した region editor」は区別する。

有限 surface は BlankTap の captured region context、tap admission / final guard、
region editor 開始・入力・終了、conditional chrome の former point 回帰。
text editor は従来どおり admission を拒否する。保存、viewport、pointer ownership、
focus / IME、region editor の UX は変更しない。開始/入力/終了 edge は pending だけを取消す。
region context の identity と content の一致を final guard に加え、stable editor の
confirmed single と pending 中の region input 変更による取消を検証する。

判定: **BOUNDED_CORRECTION**。同じ blank arbitration authority / review / rollback 境界の
追加1 roundに限定する。補正後に material correction が必要なら再び HOLD/checkpoint。
中断された full の blind rerun はせず、補正後に新候補を固定して全件を検証する。

region 補正後の class focused は fresh exact XML **3/0/0/0**。
続く TextEditorDismissalTest は exact **27/1/0/0**。
失敗は selected single の line 658、selection semantics 読取り後の real uptime < timeout 前提。
選択保持の assertion は通過し、deadline 前の読み取りにかかる native 操作時間が timeout を超えた。

## single 観測 clock の HOLD / completeness checkpoint

family は single confirmation の検証 clock。有限 surface は new Draft single と selected single の
2 testcase、共通の native first tap、既存 stale / region single の clock 制御、native double pair。
single coroutine は Compose mainClock、double eligibility は MotionEvent uptime を authority とする。
single の期限前の semantics / Activity 読取り完了を wall clock 300ms以内と仮定しない。
stale / region single と同じく clock を固定し、first UP 後に固定3 frameを処理して
deadline 未満・Draft 不在・選択保持を観測し、platform timeout 分だけ進めて確定を確認する。

判定: **BOUNDED_CORRECTION**。追加1 round はこの2 testcase の clock のみ。
production、double pair の native timing/slop、既存 #71 の期待値は変更しない。
再検証後に新しい material correction が必要なら再び HOLD/checkpoint。

補正後の selected single / new Draft single はそれぞれ単一 selector の fresh exact XML
**1/0/0/0**。region class **3/0/0/0**、文字 class の他26件の成功と併せて focused の
coverage を確認した。全 class の27/0/0/0とは記録しない。
lint / unit **147/0/0/0** / debug / androidTest build は別 invocation で成功。
最終候補の instrumentation source census は重複なしの **161 class#method**。

## 最終検証

候補 `0cd6dd5` の full Pixel9/API37 は fresh exact source/XML **161/1/0/0**。
実行前後の head と source hash は一致した。唯一の failure は
`ViewportHistoryInteractionTest#blankDoubleTapAndRegionFitHaveOneEntry` の zoom 不成立。

## native double fixture の HOLD / completeness checkpoint

blank double の有限 surface は TextEditorDismissalTest の platform interval / 8px pair、
SemanticNavigationTest の 0/40/80/120ms MotionEvent sequence、ViewportHistoryInteractionTest
の待ち時間なし2 pair。最初の2 surface は同じ full で成功し、viewport / content history も不変。
失敗した fixture は first UP → second DOWN の platform minimum を保証していない。
実測 interval の記録もないため、過去の「double」名だけで valid double と判定しない。

判定: **BOUNDED_CORRECTION**。追加1 round は ViewportHistoryInteractionTest の native
event timestamp を返す helper と当該 testcase のみ。platform minimum を待ち、native
UP/DOWN interval を minimum..timeout で検証し、8pxの shifted pair が slop 内であることを
確認する。settled animation 後に既存の history 1 entry / content 不変を観測する。
production、倍率・animation、履歴の authority、他 gesture の入力は変更しない。
再検証後の新しい material correction は再び HOLD/checkpoint。

補正後の ViewportHistoryInteractionTest class focused は fresh source/XML exact
**10/0/0/0**。platform interval / shifted slop の前提、double zoom と region fit の
1 entry、他の history regression を確認した。production は `0cd6dd5` から不変。

## main 更新の HOLD / authority completeness checkpoint

作業中に main が `5b5c7d6`（PR #96、共有取り込み）へ進み、旧 base の PR が conflict になった。
旧 base 向け `a806a77` full は中断し、最終証跡に採用しない。旧 head の CI も再利用しない。

有限 surface は CanvasScreen の merge conflict、既存 externalInteractionBlocked / saveBlocked、
import readiness と manual gesture / animation guard、pending の final check / observer、
external block の stale single 回帰、Spec 番号と source/XML census。
main の共有・保存・schema・readiness authority を保持し、pending は既存 external block でも取消す。
新しい共有機能の設計・保存経路は変更しない。Spec 011 は main の共有機能に割当済みなので、
この作業成果物を Spec 012 へ移す。初期 baseline の RED はそのまま有効な歴史的証跡とする。

判定: **BOUNDED_CORRECTION**。追加1 round は main の取り込み、上記 pending guard の適合と
その回帰、Spec rename / census に限定する。新候補を固定して focused / floor / full GMD /
current-head CI を確認してから canonical review を依頼する。新たな material correction は HOLD/checkpoint。

main 適合後の external block stale / native shifted pair は各 fresh exact XML **1/0/0/0**。
lint / unit **163/0/0/0** / debug / androidTest build は成功。Room schema は最新 main との差分なし。
新しい source census は重複なしの **191 class#method**。旧 head の161件結果とは区別する。

## 最終 gate

final candidate 固定後に unit/lint/debug/androidTest build と full Pixel9/API37 GMD を行う。
source testcase 集合と fresh XML の class#method 全件、device、counter、head / source 不変を照合する。
current-head canonical review と unresolved thread 0 の証跡がない間は MERGE_READY としない。
