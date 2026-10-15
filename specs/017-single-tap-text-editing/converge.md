# #111 検証と引き渡し

## Task Contract

- 目的: 承認済みの通常1タップ編集を実装し、明示選択と複数選択を維持したDraft PRを提供する。
- 停止点: 実装、関連ローカル検証、通常PR CI、レビュー準備、固定署名APKと実機手順の提示。merge、Issue close、Ready化は行わない。
- 開始main: `4ee73c85ac0937bcb993717670693368e223c8f8`。#110 / PR #179の作業branchと入力routerは変更しない。
- 新規テスト基盤、quarantine/fullの追加起動、2026-10-23の見直し前倒しは行わない。

## 有限censusと変更境界

| Surface | 開始mainの契約 | 今回の変更 / 保持 |
| --- | --- | --- |
| 通常本文・見出しのtap | 選択後の再tapで編集 | 0/1件選択なら同じ対象を直接編集 |
| 複数選択中のtext tap | 選択済みは解除、未選択は単独選択に置換 | 選択済みは解除、未選択は追加。どちらも編集なし |
| semantics primary | 未選択は「選択」、選択済みは「編集」 | pointerと同じpure判定。「編集」「選択に追加」「選択から外す」。座標再hitを避け対象IDで起動 |
| 明示選択 | custom action / lasso / 長押しrelease追加 | text長押しメニューへ「選択」を追加。既存custom actionも保持 |
| 移動・削除・共有 | grip / 長押しdrag / menu / 選択共有 | 明示選択後の既存経路を保持。新規保存形式なし |
| 検索・Semantic Zoom | 検索はcamera移動、projectionが可視性を所有 | 検索の自動編集なし。hiddenのguardを保持 |
| editor外側 | 同じgestureを消費し確定/終了 | router / commit / cancelは変更しない。別textへのtapも終了だけ |
| blank / ink / 作成tool | 各既存ownerが処理 | tap / double tap / stylus / lasso所有権を変更しない |
| 保存・Undo・ack | 保存guardと既存sessionが所有 | primary起動でもlive guardを確認、選択/開始だけで保存しない |

既存fixtureの旧選択入口のみを、custom actionに変更した: `EdgeAutoPanTest`、`ViewportHistoryInteractionTest`、`NeutralInteractionTest`、`TextEditorDismissalTest`、`LongPressGestureTest`、`OffscreenIndicatorsTest`。`BoardListScreenTest`の既存編集入口は1回へ変更。既存assertionとannotationは保持する。

## ローカルで確認した結果

- `TextPrimaryActionTest`: 4件成功。
- `testDebugUnitTest`: 198件、failure/error/skipped 0。
- `lintDebug`、`assembleDebug`、`compileDebugAndroidTestKotlin`: 成功。
- suite validator: planning 272、PR smoke 68、non-quarantined 234、quarantine 38、full 272。identity/annotationの増減なし。
- validator自身のPythonテスト13件成功。公開境界とdiff whitespace検証成功。
- Windows GMDの既知profile-context制約の調査・回避基盤は追加せず、Android runtimeは通常PR smokeのfresh receipt/XMLを確認する。

## head固定後の証拠

実際のPR head、base鮮度、通常CIのrun/attempt、jobのRUN/SKIP、artifact digest、receipt/XML、canonical review、APKの署名/版は、[Issue #111](https://github.com/reitojike/think-canvas/issues/111)からリンクするDraft PRの検証記録を参照する。このファイルのローカル成功をAndroid runtime成功と読み替えない。

代表実機、TalkBack、旧方式との実機比較は未確認。[quickstart](quickstart.md)に手順を記載し、Issueの対応するACは未完のまま残す。

## 最初のPR smokeと限定補正

run [38693659347 / #537 attempt1](https://github.com/reitojike/think-canvas/actions/runs/38693659347)はfresh exact 68/1/0/0のvalid red。新規に追加したprimary actionの統合assertionと他66件は成功し、`OffscreenIndicatorsTest#searchAndSelectionDeduplicateAndKeepStableAccessibleOrder`が旧通常click選択のためeditorを開き、indicator setupで停止した。

補正1の範囲は同じOffscreenIndicators fixtureの選択入口だけ。8か所のtext primary clickとfamily分岐1か所を既存custom actionへ移し、別textへ置き換えるケースは旧選択の解除と対象追加を明示する。production、assertion、timeout、identity/annotation、selector、quarantineは変更しない。新しいheadの通常CIで再検証し、旧runのgreen部分を最終証拠へ流用しない。
