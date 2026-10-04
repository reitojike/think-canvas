# 検証と収束

## 色Undoの操作前提の収束（2026-10-04）

head2697a2e9e372a254cf2fcdb0362a20214d157136 / CI37190430420は基本job成功、native153/1/0/0、missing/extra/duplicate0。152件成功し、元の検索offscreen Undoとmulti-selectionの失敗は解消。唯一のcolor Undo回帰はcenterY1472.825→1616.3251で失敗した。artifact11298688736、digest `sha256:5a305c556b155f5e61b887f8a601469efaa568cd6c2029f4bfb2df59454f9871`、XML SHA256 `6651F68D88A79B92F0CE3311E172989DDF993B861D0FEAD194E0036D748832D2`。このheadではcanonical/mergeを行わない。

[有限checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5978484098)では、現行mainでも検索中の色editがfocus effectを再実行し得る一方、test baselineが色edit前だったoracle不足を確認した。標準OBS run20261004T091856Z-a83e2ef94cfa45fe993bb2d105e038e2ではedit前/後・Undo admission・Running・IME復帰/Back・Undo後のworld focus/scaleが同一で、旧CIの143.5差は再現せず、具体的な根因は未確定のまま記録した。1/0/0/0、XML `EDC2E70514C5C722296DED1A79849A6160877F7A9FBC333BD1971342AAA2329D`、owned/sourceUnchanged=true、介入0。独立した下位モデルのread-only点検でもUndo直前の観測が不足すると確認した。

T018は一methodのbaselineを色edit後の既存waitForIdle後へ移し、Undo直前に対象が画面内であることをassertする。FR-006の『Undoそのものではcamera維持』を同じ3world-unit/.005scaleで照合し、内容復元/実Room/保存一回を維持する。probe/phaseObserver/logを除去したfocused run20261004T092336Z-de86ea2c755f4e0d91cbaadbfb4dea72は1/0/0/0、XML `BC98720C395F842B54C70ABF12A882929BABD057F690FF9F8C8286233F9E2420`、owned/sourceUnchanged=true、介入0。製品/search effect/geometry filter/入力/CI/launcher変更なし。旧CIの具体原因をPASSで解決済みと推定せず、更新head全CIで正しいUndo前後の契約を最終確認する。

診断除去後のlocal lint/unit/debug/androidTest buildは成功（1m9s）、単体139/0/0/0、公開境界214ファイルPASS、diff check成功。10FR/4SC/12scenario、有限設計5項目、Constitution5原則と既存18taskをread-only再照合し、追加buildable残差0。T012/T013/T016/T017/T018の最終CI/review/deliveryは未完了を保持する。main a116374bへbehind0、schema/依存/CI/launcher差分なし、probeなし、native source153件を維持。候補commit後はsource/docsをfreezeし、更新headの両CIを確認してからcanonical reviewを依頼する。

## 統合headの失敗分類とnative fixture補正（2026-10-04）

head `375de42a9c50955ac6c77fc5ec6ba87d3fdc4b11`のCI37187623444は基本job成功、native 153/2/0/0（missing/extra/duplicate 0）。XML SHA256 `55A49DCAF426C906F059D2F6F8C557EC85039D96B7F9839DFABA6FD0600A0132`。[有限checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5978097837)に従い、検索中の内容Undo/Redoのfixtureは実際のRoom完了をrequest-specific gateで確認し、既存inputAllowed復帰による検索IMEのnative readiness、IME Back、非表示を経てview操作する。製品のguard・保存処理は変更しない。

この補正の標準focused `visibleAndOffscreenUndoRedoFocusWithoutExtraContentHistoryOrSave`はrun `20261004T082744Z-92f779439ac041608e74eab39075e18e`で1/0/0/0、owned/sourceUnchanged=true、介入0。XML SHA256 `55C7DC3F452DAE44CCC0B86CB3F357B05C6EC67FD26D89C3FAFAAEA6F8B11AA2`。T016と最終deliveryは全CI成功までpending。残るmulti-selection drag preview→commitの失敗は別の有限観測で分類し、原因未確定の旧Issue78 closureを変更しない。

共有helperの検索結果位置・一entry回帰も標準focused run `20261004T083617Z-0b0c8d81459445af8ddbff30829b985a`で1/0/0/0、owned/sourceUnchanged=true、介入0、XML SHA256 `A8A525A089338EC15CA24DD4DCE6FBCAEB54F1E47C1BCE29D2EF6C63B01DD9F4`。

multi-selectionは[release oracle checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5978246199)で既存Spec008 FR-004/005/006/010・SC-002/004に閉じた。通常観測は1/0/0/0だが非再現だけで解決とはしない。高速入力の制御RED（run `20261004T084403Z-ee2d81297d154f20b55c5b2faca2cae2`、1/1/0/0、XML `697549AD1747876F1E2A023B70E0975B0D6C31BBC883DE5D3DFD546F431BE21A`）は実配達MOVEが予測位置1553.5、UPが元の1077、preview1493.4→確定1017.4だった。native座標を基準とするpreview/release比較は2px内で一致し、同じ制御入力のGREEN（run `20261004T084843Z-6af2e86fee9d424da75aeeba5f522833`、1/0/0/0、XML `9EB175000B55706DE1F16AF7F85AAE922169AFB76CEE3E13D307BC5D00BC1B14`）で一回のUndo/Redo/saveまで確認した。両run owned/sourceUnchanged=true、介入0。旧CIの実配達座標はないため具体的な448px差の歴史的根因を確定したとはしない。

finalのT017差分は軽いtest-only Window.Callback delegateとnative oracleだけ。開始→previewとpreview→UPの表示、layout/scale不変、全選択delta、Room/保存数/履歴一回を維持する。診断用高速timestamp/JSON/logは除去し、注入座標/時刻/経路・製品コード・CI/launcherは元のまま。更新headの基本検証、全153件CIとcanonical reviewを最終gateにする。

診断除去後のlocal lint/unit/debug/androidTest buildは成功（1m5s）、単体139/0/0/0、公開境界214ファイルPASS、diff check成功。10FR/4SC/12scenario、有限設計5項目、Constitution5原則と既存17taskをread-only再照合し、追加buildable残差0。T012/T013/T016/T017の最終CI/review/deliveryは未完了を保持し、reviewer-owned UX checklistは未評価のまま。最終native source153、schema/依存/CI/launcher差分なし。以後はcommit済み候補をfreezeして両CIとcanonical/base/threadを確認する。

## PR92統合後の最終gate（2026-10-04）

[PR95 / Issue93](https://github.com/reitojike/think-canvas/issues/93#issuecomment-5977070810)と[PR92 / Issue91](https://github.com/reitojike/think-canvas/issues/91#issuecomment-5977858342)はmerge済み。main `a116374b3f39fe3ad46b200a05d49296f38efd68`をPR90へ競合なく統合した。IMEのfollow-upは独立したdeliveryとして完了しており、Issue79のPO判断はIssue77のgateへ含めない。

統合後のread-only convergeは10FR/4SC/12scenario、有限設計5項目、Constitution5原則と既存15taskを再照合し、追加buildable残差0。Spec010 tasks.mdはbyte-identical（SHA256 `C151E1607A61A88446AE2E1BE6A7FA11820D90EC1D69BBAE80A85EF996F9B5BC`）、空phase追加なし。T012/T013のCI/review/merge/closureは実際のdeliveryまでpendingを維持する。native sourceは153、unit sourceは139。過去のhead60fのCI失敗は履歴として保持し、最終gateには統合後の新headの両CI/fresh XML/canonical/base/threadだけを使う。

統合working treeのlocal lint/unit/debug/androidTest buildは成功（1m47s）、unit XMLは139/0/0/0、公開境界214ファイルPASS、schema/依存/CI/launcher差分なし。標準focused `ViewportHistoryInteractionTest#blankDoubleTapAndRegionFitHaveOneEntry` はrun `20261004T075838Z-c5108353442542cdb6be6763bc70ccdd`で1/0/0/0、owned/sourceUnchanged=true、介入0、XML SHA256 `3E7C1D09834FC7E378FA1326E35015BD1DDCF0E355F779F472ACE16D00F2A0DB`。最小倍率のback/forwardとregion fitの1entryを統合後も確認した。GMD終了までsource/docs固定、以後は検証記録だけ更新。最終merge gateのfull CI/reviewはcommit済み新headについてPR90へ記録する。

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
