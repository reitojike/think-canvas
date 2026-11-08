# ボードの保存とデータ寿命

現在の保存境界をまとめます。利用者に見える操作は [ボード管理の仕様](../../specs/005-board-lifecycle/spec.md)、
[共有取り込み](../../specs/011-share-text/spec.md)、[画像要素](../../specs/013-image-element/spec.md)を参照します。
以下は現行実装の責務であり、移行計画や実行済みテストの記録ではありません。

## 永続データと移行

[CanvasDatabase](../../app/src/main/java/com/thinkcanvas/data/CanvasDatabase.kt) は Room 3 と
AndroidSQLiteDriver を使い、`thinkcanvas.db` を開きます。現行 schema は v4 で、
`boards`、`text_elements`、`spatial_elements`、`arrow_elements`、`ink_strokes`、
`share_import_receipts`、`image_elements` を持ちます。列・型の正本はこの実装と
[export済みschema](../../app/schemas/)です。

- v1→v2 は Ink、v2→v3 は取り込みの完了receipt、v3→v4 は画像要素の表を追加します。
  `open` は3つのmigrationを登録し、破壊的な再作成へのfallbackを設定しません。
- 初期資料の「単一ボード」「四表」「schema v1/v2」は当時の範囲です。現在の保存範囲や
  現在のインストールからの更新手順として使いません。
- 各要素行は `boardId` を持ちます。文字・図形・矢印・Ink・画像は同じ
  `replaceAll` transaction で対象ボードの行を置換します。ボードが存在しない保存、
  渡した要素に別ボードのIDが混じる保存は拒否します。
- 共有テキストと画像の取り込みは `replaceAllForShare` で内容とreceiptを同じtransactionに
  保存します。同じ要求のreceiptがあればboardId/elementIdを照合し、再挿入しません。
  ボード削除でreceiptを削除しないため、過去の要求を再生して内容を復活させません。

## ID、順序、複製

- 新規ボードは2以上のランダムなLongを使い、現存IDとの衝突を検査します。既存の初期ボード
  id=1を新しい採番に使いません。この方式は過去の全削除IDとの数学的な衝突不可能性を
  保証するものではありません。
- 更新時刻は現在時刻と全ボードの最大値+1の大きい方とし、一覧は更新時刻降順、同値なら
  ID降順です。保存・名前変更・新規作成で更新し、単なる読込では更新しません。
- [複製](../../app/src/main/java/com/thinkcanvas/board/BoardDuplication.kt)は全要素とInkの線を
  新しいIDにし、接続矢印のtarget IDも写します。座標・内容を保持し、画像assetIdは共有します。
  画像ファイルそのものを複製する必要はありません。

## 操作と保存の寿命

[CanvasStore](../../app/src/main/java/com/thinkcanvas/data/CanvasStore.kt) はアプリケーション単位の
インスタンスで、IO coroutineと単一の操作queueを所有します。保存・読込・複製・削除・
設定commitをその順序列に通します。`save(boardId, snapshot)` は対象IDとsnapshotを受け取り、
画面の現在選択先から保存先を再解釈しません。

- `BoardSnapshot` は確定済みの文字・図形・矢印・Ink・画像です。viewport、選択、入力中の
  editor draftはDBの要素行と別です。
- [BoardState](../../app/src/main/java/com/thinkcanvas/canvas/BoardState.kt) のUndo/Redoは
  セッションのbefore/after snapshotで、編集履歴は80操作を上限とします。DBには履歴を保存しません。
- Storeの操作queueへの投入後、呼び出し側が待機をやめたことだけで保存が取り消されたとは
  判断できません。共有の取消境界は専用の `executeShareOperation`、画面の保存成功確認は
  BoardSessionのacknowledgementで扱います。これらをUIの表示フラグだけで置き換えません。
- `initialized`、`lastOpenedBoardId`、案内の対象・完了は端末内SharedPreferencesです。
  初回だけ初期ボードを用意し、初期化後に最後のボードを削除しても自動再作成しません。
  最後に開いたIDの対象がなければ一覧へ戻すために設定を除きます。

## 画像素材と取り込みの境界

保存queue中のsnapshotの画像はleaseで保護します。回収対象を決める根はDBのasset参照、
現在/Undo/Redoを含むsession所有者の参照、lease、accepted checkpointです。複製したボードの
片方を削除しても、もう片方の参照がある素材を回収しません。回収失敗だけで耐久保存の成功を
失敗に変えないよう、cleanupと保存結果を分けます。

画像のID・寸法・decode上限・checkpoint各phaseの詳細は
[画像データ契約](../../specs/013-image-element/data-model.md)、テキスト取り込みの
task復元とretryは[共有データ契約](../../specs/011-share-text/data-model.md)を参照します。
これらの詳細までこの文書へ移行したとは扱いません。

## 画像出力は読取専用

[SharePlan](../../app/src/main/java/com/thinkcanvas/board/SharePlan.kt) は固定snapshotと選択集合から
出力対象と範囲を作ります。名前付き囲み内の対象を追加し、接続矢印を条件に従って含めます。
本文・囲み名の描画寸法を解決し、上下左右へ `max(24, 長辺×0.05)` の世界座標余白を加えます。
1〜2 px/世界座標単位、長辺4096px・16MP以下に収め、1 px/単位でも収まらない対象、
空の対象や非有限の座標を拒否します。これは製品の出力上限であり、Androidの固定制限ではありません。

一覧の遠景表示と出力用previewは別です。出力の計画は元のDBや編集履歴を変えません。
PNG描画とAndroidへの受け渡しの操作契約は[ボード管理の仕様](../../specs/005-board-lifecycle/spec.md)を参照します。

## 検証の入口

- [BoardPersistenceTest](../../app/src/androidTest/java/com/thinkcanvas/data/BoardPersistenceTest.kt):
  reopen、別ボードの分離、削除後の遅延保存の拒否。これだけで全旧schemaのmigration検証とはしません。
- [BoardDuplicationTest](../../app/src/test/java/com/thinkcanvas/board/BoardDuplicationTest.kt):
  複製時の配置と接続先。
- [SharePlanTest](../../app/src/test/java/com/thinkcanvas/board/SharePlanTest.kt): 出力対象と寸法。
- 実行するsuiteと合否は[現行検証手順](../runbooks/pre-pr-verification.md)に従います。
  ソースの照合、エミュレーターの成功、実機確認は別の証拠です。実行結果は対応するIssue/PRに記録します。
