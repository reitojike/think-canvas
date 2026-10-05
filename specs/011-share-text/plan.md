# Implementation Plan: Android共有からテキストを取り込む

**Branch**: `codex/issue-79-share-text` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

## Summary

Issue79のA/A・Bを実装する。受信→保留→内容/board確認→表示中視点で一回追加→既存保存ownerによる耐久保存、の順にする。一般の編集履歴・保存・視点authorityを分岐させない。

## Technical Context

- **Language/Version**: 現行Kotlin/AGPを維持。API 26以上、compile/target 37。
- **Primary Dependencies**: 現行Compose BOM 2026.09.00、Activity 1.13.0、Room 3.0.3、SQLite 2.7.1、coroutines。追加/更新なし。
- **Storage**: Room schema 2→3で完了receiptを追加。保留本文はbackup対象外private file、task復元tokenだけをActivity saved stateへ置く。
- **Testing**: JUnit、実SQLite/Room、Compose instrumentation、既存Windows GMD/Android CI。
- **Target Platform / Project Type**: 単一MainActivityのAndroidアプリ。既存Page/BoardSessionを利用。
- **Performance Goals**: file IO/Roomはmain thread外。本文をBundleへ入れない。常設chrome/ネットワーク処理なし。
- **Constraints / Scope**: 一件だけ保留、一要求一Undo/一save、失敗の自動retryなし。画像、複数共有、URL取得、cloud inboxは対象外。

## Constitution Check

| 原則 | Phase 0前 / Phase 1後の判断 |
| --- | --- |
| I authority | Issue79 A/A・B、merge済Spec005/006/007/010。新機能は本specで定義。 |
| II Standard-first | Intent/onNewIntent、ViewModel、task saved state、Material dialog、Room transaction。限定判断を下記に記録。 |
| III 空間配置 | destination視点のworld中央を一度取得。既存要素の移動/整列なし。 |
| IV local-first | private file/Roomだけ。本文log/送信/URL取得なし。 |
| V spec先行 | spec→clarify→plan→checklist→tasks→analyzeをコード前に完了。 |

違反なし。Phase 1後も同じauthorityを維持する。

## 有限surface

1. `AndroidManifest.xml` / `MainActivity.kt` / `BoardListScreen.kt`: 受信、既存task、現在Page、候補選択、一覧のmodal保護、UI継続。
2. `share/ShareImport*.kt`: parser、task private checkpoint、preview/確定/復元。外部extrasは内部状態のauthorityにしない。
3. `BoardSessionViewModel.kt`: 固定IDで一回追加、request-specific ack、失敗/明示retry。一般保存coalescingを維持。
4. `CanvasScreen.kt` / `ViewportHistory.kt`: 中立状態をlive再判定、準備済み視点を使用。新geometry式なし。
5. `CanvasStore.kt` / `CanvasDatabase.kt` / `schemas/`: 全置換+receiptの一transaction、2→3 migration。
6. unit/Room/instrumentation: 受信・確認・保存・復元・取消・既存編集保護の境界。

## 設計

### 受信とtask

MainActivityにACTION_SEND/text/plain filter/onNewIntentを追加。未確定editorのownerを維持するためsingleTaskを現在の単一Activity/Pageに限定して使う。外部Activityを上に積む画像exportのresult待ちでは標準task挙動により取消resultとなり得る。既存の取消cleanupとlauncher/Back/resultを回帰確認する。独自task managerや第二save writerは作らない。

documentLaunchModeはneverとし、送信元のNEW_DOCUMENT/MULTIPLE_TASK指定でも同じownerを使う。[Android activity manifest](https://developer.android.com/guide/topics/manifest/activity-element#dlmode)の標準指定で制御する。

新onNewIntentは新要求、本文一致で排除しない。同taskのonCreate復元はtrusted saved Bundleのtask tokenからprivate checkpointを読む。送信元が提供しないdelivery IDを推測せず、この境界でfresh操作と復元操作を分ける。

task tokenは要求IDとは別で、そのtask中に安定する。checkpointを各変更時にAtomicFile更新し、最後のSTOP後の確定/取消を古いBundleに戻さない。Bundleにはtokenだけ。default SavedStateHandleのIntent default argsを使わず、外部extrasから内部token/完了状態を取得しない。

AtomicFile.finishWriteの内部sync/rename失敗は例外にならないため、flush後のfd.syncと更新後のread-back一致で成功を確認する。失敗時は既存Failed/manual retryへ戻す。UTF8変換で原文が変わる入力も成功扱いしない。別のcheckpoint writerは増やさない。

saved state復元でtoken/fileが欠落・不明でも初期Intentをfresh要求として再処理しない。再共有を案内する。onStopを取消判定にせず、finishing callbackは未commit取消の補助手段に限定する。

`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 付きの起動はsaved Bundleがなくても復元として扱い、旧本文をfresh要求にしない。onNewIntentもこのflagは受信として扱わず、その他の到着は引数から直接処理する。元のtask起動Intentを新しい受信で置き換えない（復元authorityはtoken/file）。[Android Intent reference](https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)に従う。flagもBundleもない外部到着に送信元提供のdelivery IDを推測しない。

新taskは新tokenを使い、古いfileをinboxとして検索しない。取消/成功/finishingでは本文を消し、新規起動で不要fileを掃除する。task終了通知がなくても旧tokenを使わずBを守る。共有専用queued/in-flight operationだけを取消し、commit済みcontent/receiptは巻き戻さない。

finishingの本文消去はshare VMの進行中AtomicFile IO完了後、既存StoreのIO actorへterminal更新を渡す。Activityのscope取消しで消去が中断されたり、古いfile更新が後から本文を戻したりしない。checkpoint準備/terminal更新の失敗もFailedとして明示retryを待ち、画面effectでfile書き込みを反復しない。

### destinationとpreview

warmはPage.Board、cold/listは最後に開いた有効boardをread-only照会、なければpicker。共有起動で通常初回restoreの自動board作成を行わない。0件なら既存createを明示的に実行できる。

Material dialogで全文とboard名を必ず確認、pickerで変更可能。preview本文はread-only。Androidの利用前編集推奨を検討したが、v1はIssueの確認/取り込みに閉じ、取り込み後の既存editorで編集する。第二editor/draft復元を増やさず、全文scroll/TalkBackを提供する。

confirm時はlive guardを再検査し要求状態を先に切り替える。destinationを開き、測定/初期fit済みViewportHistory.focusのworld中央を取得。固定text ID/内容/位置をcheckpointへ確定してからBoardState.applyを一回、既存保存ownerへの要求を一回。cancelはcontent/Undo/Redo/save不変。

### 既存編集保護

editor/IME/tool/search/gesture/animation/modal/preview、Running/Failed、pending editor ack中は保留して通知。保留中は元操作を完了できる。Canvasから基礎中立状態boolとlive guardをMainへ公開し、確定callbackでも再判定する。

preview/確定待ち/取り込み保存中は外部modal guardを既存操作判定へ接続し、composition後の古いactionも拒否する。自身のmodalで準備判定が永久falseにならないよう、基礎中立状態と外部blockを分ける。

### 保存と復元

Room receipt(requestId PK, boardId, elementId)は本文なし。全replaceAllとinsertを一transaction。完了済みIDなら全置換も再実行しない。receiptはUndo/要素/board削除と独立し、旧要求から内容を復活させない。

BoardSession pending ackにreceiptを結び、共有専用save callbackでStoreへ渡す。普通のsave/ack/coalescingは維持。共有完了まで操作制限し、retryは同じ確定patchを使う。config recreationは同じVM/ackを利用する。

fresh ownerはまずreceiptを照会。完了なら最新Room内容を使い旧patchを再適用しない。未完了は最新Room boardに固定patchを一回加えてFailedへ復元しmanual retry。旧全snapshotを保存して後続編集を上書きしない。確定board消失は失敗、別boardへのsilent fallbackなし。

照合はAcceptedだけでなく古い未確定previewにも適用する。request/element identityが一致する完了receiptを優先し、確定済みpatchだけboard identityの一致も要求する。未確定candidateは保存先のauthorityにしない。

共有専用Store operationはDeferred cancelをqueued/transactionへ伝播し、actor/普通saveはcancelしない。commitとの競合はreceiptをauthorityとし、UI ack未表示でも成功済み内容を消さない。

## Project Structure

```text
specs/011-share-text/
  spec.md, plan.md, research.md, data-model.md, quickstart.md, tasks.md
  contracts/share-target.md
  checklists/requirements.md, lifecycle.md
app/src/main/java/com/thinkcanvas/
  MainActivity.kt, BoardSessionViewModel.kt
  share/ShareImportRequest.kt, ShareImportCheckpoint.kt, ShareImportViewModel.kt, ShareImportDialog.kt
  data/CanvasStore.kt, CanvasDatabase.kt
  canvas/CanvasScreen.kt
app/src/test/java/com/thinkcanvas/
app/src/androidTest/java/com/thinkcanvas/
app/schemas/com.thinkcanvas.data.CanvasDatabase/3.json
```

## 検証とdelivery

[quickstart.md](quickstart.md)を使用。ActivityScenario.recreateをprocess killの証明とは呼ばない。fresh owner + parcelしたtoken +実Room/fileで復元protocolを検証し、実OS killを含む実機横断確認は親81で区別する。

既存BoardListScreenTestの名前変更・再生成回帰では、native IME表示、保存ボタンの安定した画面位置、入力値を確認してから同じnative clickを行う。保存開始gateの時間と一回の保存/再生成後のassertを維持する。旧失敗ログだけからクリック遮蔽やguard拒否の原因を断定しない。

現行headのlocal basic/Room/schema/public boundary、標準GMD、両CI、最後の依頼後canonical review、最新base、全thread解決を収束。許可済みmerge後に最新Issue79 ACを個別判定する。

### 非同期admissionと古いchrome操作の限定補正

MainのDeferred候補照会は現在のPage/requestを捕捉し、boards/last照会後にもlive guardを検査してpreviewを表示する。`share/SharePreviewAdmission.kt` は同じUI scope内のこの境界を単体で制御するための関数で、状態ownerや保存writerを追加しない。Canvasはzoom clickと検索移動focusMatchにlive制限を接続する。pointerのchrome DOWN-UP dispatchと既存検索入力は維持する。既存native回帰を拡張し、保存制限解除後の通常検索移動も確認する。

### 検索終了後zoomの検証前提

既存SemanticNavigationTestは検索終了後のnative IME非表示/inset0とCanvas bounds安定を観測してから同じzoom actionを行う。Canvas resizeによるanimation終了は変更せず、fixtureの通常操作前提を先に確認する。期待倍率・native accessibility oracle・wait上限は維持し、失敗時の現在zoomを診断する。


### 一覧画像共有Loading fixtureの初期表示前提

既存回帰は初期一覧の表示を待ってからStore actor gateを挿入し、startupのrestore/list読み込みをgateに巻き込まない。finallyの解除を保証し、製品のserialized actor/Loading guardと既存assert/待機上限を維持する。

## checkpoint IO の限定 follow-up

PR #97 の CI で空の共有 picker から作成後の preview 待ちが失敗し、同時期に AtomicFile rename failure を観測した。因果を断定せず、別 PR の shared checkpoint / picker 操作 readiness に閉じて扱う。
[Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) は排他制御を提供せず、呼出し側の保護を要求する。現行の ViewModel IO と Store の finishing-task cleanup を維持し、単一 app process の checkpoint read / write（read-back まで）/ discard を共有 JVM monitor で保護する。UI thread へ IO を移さず、Room / save writer / task restoration / 要求 identity / schema は変更しない。

回帰は native AtomicFile の startWrite を latch で固定し、同 token の別 owner read と、別 token への task discard を割り込ませる。公開の2引数 constructor は維持し、internal constructor だけで同じ native primitive を渡せるようにする。generic storage backend や test専用状態ownerは追加しない。旧 file が破棄される前の write/read-back 完了と、current token の本文保持を照合する。

picker の操作前提は visible text だけでなく phase=PICKER / writing=false と利用可能な button を確認する。既存 raw guard、操作回数、10秒上限、preview / content / 保存の期待値を維持する。

## picker / preview lookup の stale result follow-up

PR #97 head0e7fa10のrequired CIは194/1/0/0で、empty-createのpreview待機が失敗した。今回のlogにはAtomicFile例外がないため、IO排他の再発と断定しない。Process #36の有限checkpointにより、共有先lookupの別responsibilityとして分離する。

MainActivityのPICKER/PREVIEWはstore.boardsのsuspend後にcaptured requestとlive phaseを混在させる。pickerの古いqueryが新board選択後に戻ると、古いdestination=nullで新previewをmissing扱いし得る。既存SharePreviewAdmissionの局所関数として、captured ShareImportStateのidentityをawait前後に再検査し、同じstateの場合だけ一覧とmissing判定をpublishする。effect cancellation/recompositionより前のguardをauthorityにする。新状態owner、writer、generic coordinatorは追加しない。

controlled unitではqueryをDeferredで止め、pickerからnew-board previewへの遷移、cancel/別要求への遷移、current previewの既存/欠落destinationを照合する。実際のempty-create native回帰と全件GMDも確認する。board create、checkpoint IO、Room/save/Undo、blank arbitration、schema/dependenciesは変更しない。新material findingは補正前にHOLD/checkpointする。

controlled raceは補正前3/2/0/0、補正後3/0/0/0。実画面のempty-create focusedはfresh exact1/0/0/0。取消fixtureはEMPTYを明示し、固定final headのfull unitでも確認する。旧CIのtimeout原因をこの結果だけで断定しない。
