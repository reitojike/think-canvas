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
