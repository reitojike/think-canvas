# 視点履歴と編集変更の表示補助

利用者の操作契約は[視点履歴の Living Spec](../../specs/010-viewport-history/spec.md)を参照します。
ここでは camera と内容履歴を分ける状態所有者、記録の境界、編集変更位置の表示を扱います。
保存の責務は[ボードの保存とデータ寿命](board-persistence.md)、入力sessionは
[テキスト編集session](editor-session.md)の別契約です。

## 所有者と寿命

[BoardSessionViewModel](../../app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt) は boardId ごとの
Session に BoardState、saveState、TextEditorSession、ViewportHistory を保持します。
`viewportHistoryFor(boardId, initial)` は同じ session の履歴を返します。
[MainActivity](../../app/src/main/java/com/thinkcanvas/MainActivity.kt) から CanvasScreen に渡すため、
同じ ViewModel が生きている Activity 再生成やボードの離脱・復帰では履歴を再利用します。
`discard(boardId)` はその session を除きます。process 終了後の復元や Room への永続化ではありません。

[CanvasScreen](../../app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt) は
`ViewportHistory.viewportState` を camera の状態として使います。履歴が渡されない呼び出しでは
`remember(board)` で保持するため、この場合まで Activity 再生成を越えると一般化しません。

## ViewportFocus / ViewportHistory
`ViewportFocus(centerX: Float, centerY: Float, scale: Float)`はfinite中心と0.15..3倍率。
`ViewportHistory(capacity: Int = 80)`はcameraの`viewportState`、initialized、size/density、back/forward、最後の検索groupを持つ。

- `resize(width, height, density = 1f)`: 正のfinite測定だけ受理。初期化後は旧中心を新sizeへ写す。entryなし。
- `initialize(viewport)`: 測定後の初回fit、entryなし。
- `focus(): ViewportFocus?`: 実cameraから取得。未初期化/無効ならnull。
- `record(origin: ViewportFocus?, group: Any? = null): Boolean`: 現cameraと異なるoriginをbackへ追加。同じnon-null groupはまとめ、view forwardだけclear。上限超過で古いentryを落とす。
- `back()/forward(): Viewport?`: 実現在focusを対向stackへ移しdestinationを返す。UIがanimationする。自己記録なし。
- `canBack/canForward`: Compose観測可能。

Session所有、`viewportHistoryFor(boardId, initial)`で取得。camera Stateは既存writer専用、内容/保存参照なし。

## 編集変更表示
`affectedHistoryIds(before, after): Set<String>`で全family差分と変更接続先のarrowを求める。
`historyDisplayBounds(ids, beforeGeometry, afterGeometry)`は存在するafter bounds、消失IDはbefore boundsを使う。
UI要求はbefore/expectedAfter snapshotとbefore geometryを持ち、一致しないsnapshotで棄却、一度消費。複数対象は一回fit。
接続先変更から得たarrow候補は `affectedHistoryDisplayIds` で実測before/after arrowRenderGeometryを比較する。モデル自体も表示も変わらないarrowを変更範囲から除く。要求はbefore/after snapshotを保持し、同じrepresentation条件で照合する。

## 不変条件
内容履歴/Redo/座標/query/selection/保存をViewportHistoryから変更できない。save state/ack authorityは既存owner。DB列/serialization追加なし。

## 近い視点と検索入力group

[ViewportHistory](../../app/src/main/java/com/thinkcanvas/canvas/ViewportHistory.kt) は、中心差を両視点の
大きい倍率で screen に換算して 2dp 以下、かつ倍率差が大きい倍率の 1% 以下の場合に近い視点とみなします。
`record` は無効な origin や現在視点を拒否します。同じ group の判定は参照同一性です。
検索入力が group の開始点へ戻ったときは、その group が追加した origin だけを除き、以前の履歴に属する近い origin を巻き込んで削除しません。容量超過による古い entry の削除は別に適用されます。
同じ位置への無移動は entry を作りません。stack の上限は各方向に適用し、古い entry から落とします。

`back` / `forward` は対向 stack を更新して復元先の Viewport を返します。
戻り値だけで camera が更新されるわけではなく、画面側がその target へ移動します。

## navigation の記録と停止

CanvasScreen の `animateViewport` は移動開始時の focus を保持し、完了時に target を代入してから
一度記録します。次の navigation や直接 gesture は既存 animation を止め、その到達点を境界にします。
`restoreView` は最新の操作可否を再判定してから `record = false` で復元し、自分自身を記録しません。
履歴controlの guard は保存・ack・editor・IME・modal・作成tool・preview・外部blockを参照します。
tool palette の展開だけでは履歴controlを隠さず、indicatorの抑止とは区別します。

手動 pan/pinch は最初の camera 更新前の origin を保持し、正常な終了で一度記録します。
速い pan が継続する場合は [PanFling](../../app/src/main/java/com/thinkcanvas/canvas/PanFling.kt) の
`ViewportAnimationBoundary` と origin を共有し、自然停止または割り込み停止で一度記録します。
resize/dispose は通常の animation を新しい履歴にしませんが、継続中の fling では完了した pan の
停止境界として origin を記録します。resize/dispose 自体を別の利用者操作として追加する意味ではありません。
速度判定、標準 decay、stale frame の拒否は[条件付き pan 継続の仕様](../../specs/015-conditional-pan-fling/spec.md)を参照します。

## 編集 Undo/Redo と表示要求

[ContentHistoryFocus](../../app/src/main/java/com/thinkcanvas/canvas/ContentHistoryFocus.kt) は
text・shape・image・ink・arrow の前後差分から対象を求めます。接続先の変化で表示が変わり得る
arrow も候補にしますが、モデルと実測描画のどちらも変わらない arrow は除きます。
表示 bounds は取得できる after を優先し、ない場合だけ before へ戻します。

CanvasScreen の `editHistory` は既存の BoardState の Undo/Redo を一回適用し、保存を要求します。
その際の before、expected after、before geometry で `HistoryFocusRequest` を作り、更新後の
描画geometryを待ちます。live snapshot が expected と違えば要求を捨て、表示判定した要求は一度で消費します。
変更対象が部分的に見えていて semantic にも可視なら camera を維持します。
一つでも画面外または semantic に不可視なら変更範囲を表示し、単独の text は focus、その他は fit します。
この表示移動は内容・編集Redo・保存を追加変更せず、視点履歴だけに記録します。

## 検証の入口と残る境界

- [ViewportHistoryTest](../../app/src/test/java/com/thinkcanvas/canvas/ViewportHistoryTest.kt):
  初期化、resize、往復、近似判定、検索groupの往復と所有するorigin、容量、無効値。
- [ContentHistoryFocusTest](../../app/src/test/java/com/thinkcanvas/canvas/ContentHistoryFocusTest.kt):
  差分対象、接続arrow、after/before bounds、Undo/Redoと複数対象の表示計算。
- [ViewportHistoryInteractionTest](../../app/src/androidTest/java/com/thinkcanvas/canvas/ViewportHistoryInteractionTest.kt):
  画面操作、cameraと内容・保存の分離、guardと画面寿命の結合。

テストの存在は今回の実行成功や全要素familyの実機検証を示しません。実行結果と未確認事項は
関連 Issue / PR で追跡し、IMEの未解明事象は[隔離runbook](../runbooks/ime-quarantine.md)に従います。
IMEの表示補助・入力所有権や010の過去の検証記録は今回移設していません。
