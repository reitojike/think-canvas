# 検証と収束

作業用worktreeで実施、primaryの利用者変更を触らない。Gradle/GMDは一つずつ、GMD中はsource/docs固定。結果とlocal設定はignored private配下。

- 既存runtimeで `./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`。
- 標準 `scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.ViewportHistoryInteractionTest#対象method'` は一回一method。launcher変更なし。
- unit: 復元/分岐/group/上限/duplicate/無効/resize、全family差分/接続arrow/multi/消失/Redo。
- instrumentation: 全navigation/native pan-pinch-cancel/画面内外Undo/一回save/session/guard/hidden chrome/次gesture。

FR-001/003/004/008→T003/T005/T006、FR-002/009→T004/T007、FR-005/006/007→T008/T009/T010、FR-010→T004/T007/T010。SC-001～004→T011/T012。12 acceptance scenarioは対応unit/instrumentationで検証し、head/run/XML/hash/censusを後記する。

clarify: 明示回答をspecへ反映。機能/データ/UX/非機能/依存/境界/用語/完了の重要曖昧さなし。新質問0。
analyze: tasks生成後のread-only分析で重大な未決事項をコード前に解消する。

read-only analyze結果: 10FR/4SC/12scenarioを全13tasksへ対応付け、coverage100%。CRITICAL/HIGH 0、重複/衝突/未対応要件0、Constitution5原則PASS。コード前に報告済み。

初回unitは133件中1件がFloatの249.99998/250完全一致期待で失敗。world .001/scale .0001の成分比較へ補正し、133/0/0/0、lint、debug/androidTest buildが成功。下位モデルの限定点検で検索再focusとUndo表示の競合を検出し、Undo/Redo snapshot/queryに一致する一回だけ検索auto-focusを抑止した。通常検索と内容保存単位は維持する。

最終headのAndroid CI両job成功後canonical review、current-head収束、merge、最新Issue AC個別検証、closeまで許可済み。親81実機dogfoodingは区別する。

## Focused観測

- 検索/cycle/native pan-pinch/indicatorの往復は標準launcher run20261004T002218Z-8aa1c3da0c5c42eaac564522331c984fで1/0/0/0。sourceUnchanged/ownershipPassed true、介入0。XML SHA256 7DC973A3B1F9BE9C2CF2D2D1745622BA32A5CD99FCE3BB278E11755E4AC730A0。base上の未commit実装を固定して観測した補助証拠。
- 全family visible/offscreen/deletedとmulti Undo/Redoはhead e3b2b3c12d03b532ea2e5add4dfb8a177b5058deで標準focused成功。run20261004T003012Z-c70dc1aa63bd419cb39b641378a4cd75、owned XML1/0/0/0、sourceUnchanged true、介入0。保存回数、モデルsnapshot、編集Redo、auto focusから一回の視点backを照合。
- 直前の同method失敗は編集「進む」のprefix selectorと保存待ちが不十分なfixture。完全一致のselectorとIdle待ち、Redo後の内容差分assertionへ補正し、同条件の再実行で成功。production sourceの変更は不要だった。
- convergeは10FR/4SC/12scenario、有限設計、5原則を確認し、未変更attached arrowの範囲拡大をT014へappend。実測arrow geometry比較とunit/UI回帰で補正した。最終headのCI/canonical/deliveryはPRとIssueの証跡を参照する。
最終候補のlint、unit135/0/0/0、debug/androidTest build、公開境界212候補、diff checkは成功。全family focused XML SHA256 C4C1B22EA1E02BC474099289E4EBF7F0BE59356A44CF514571BE873A0E821535。

PR90のguard focusedでdetached Material IconButtonの古いactionを非表示後に直接invokeしたfixtureがCompose playClickSoundのCompositionLocal読取で失敗した。PR上の有限checkpointに従い、save/ack/editor/toolの状態変更と同一UI turnのattached actionを検証し、hide/Failed/recreation後は表示・camera・履歴を観測する。production source変更なし。更新headでfocused/全CIを再検証する。

head d1e461dの全体CIは149件中148件成功、missing/extra/duplicate/skipped 0。残る一件は同一methodの第二Activity起動で未初期化focusを比較したfixtureの準備待ち不足。round 2はwithBoardのadmissionに測定・初期fit完了のfocus待機だけを追加し、production sourceを変更しない。旧headで失敗した既存auto-pan cancel→次panはd1e461dの全体CIと標準focused run20261004T010239Z-7655dfec406447949b34b9cbb778c5efで成功。更新headのfocusedと全CI、成功後のcanonical reviewで最終gateを判定する。

round 2の準備待ちだけではregion fit→Backの失敗を解消しなかった（focused run20261004T010616Z-f1db0e0038424c3785b8a3bfdbd0504e、owned/source unchanged、介入0）。先の診断を訂正し、第三round前にPR90上でcamera writer familyをread-onlyで全列挙した。Float origin1.1からtarget.15への補間は.14999998になり、historyのvalid範囲から外れる。唯一不足したanimation writerを既存範囲clampと正常completionのexact targetで補正し、最小倍率の往復回帰を追加するT015に閉じた。中断の到達点記録、PO authority、内容/save/schemaは維持。更新headのfocusedと全CIを再検証し、さらなるmaterial correctionが必要なら再checkpointする。

再converge: 10FR/4SC/12scenario、有限設計と5原則を再照合し、buildable残差0。tasks.mdはbyte-identical（SHA256 2FA24266A08557E7AC2B9F5A23019B5BA9466B9A468DAB80B5224484C34AF99B）、空phase追加なし。T012/T013の最終CI/review/merge/closureは未完了として保持する。
