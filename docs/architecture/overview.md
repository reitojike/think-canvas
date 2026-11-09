# 現在の構成と責務

ThinkCanvas は単一の Android app module で構成します。製品の振る舞いは
[Living Spec の一覧](../../README.md#現行仕様living-spec)、繰り返し使う手順は
[変更とレビュー](../runbooks/change-review.md)を参照します。ここにタスク計画や実行結果は置きません。

## 実装の境界

- `app/src/main/java/com/thinkcanvas/canvas/`: 世界座標のボード状態、viewport、編集と視点の履歴、
  gesture、描画、編集session、入力所有権を扱います。`BoardState` と `Viewport` を分け、表示の
  移動・倍率で保存された配置を変更しません。`CanvasScreen` が画面の入力と状態を接続します。
- `app/src/main/java/com/thinkcanvas/board/`: 一覧、ボード操作、案内、サムネイルと画像出力を扱います。
- `BoardSessionViewModel`、`BoardListActionViewModel`: 画面とボード操作の寿命・状態を管理します。
- `app/src/main/java/com/thinkcanvas/data/`: Room 3 の保存モデルと `CanvasStore`。
  現行schemaとmigrationの根拠は `CanvasDatabase.kt` と `app/schemas/` です。初期計画の表数や
  schema版を現在値として複製しません。
- `app/src/main/java/com/thinkcanvas/image/`: 画像の取り込み、端末内素材、decode、checkpointと資源管理。
- `app/src/main/java/com/thinkcanvas/share/`: Androidの共有要求の受理、確認、取り込みと保存の境界。

## 詳細設計

- [ボードの保存とデータ寿命](board-persistence.md): 永続化、ID、保存queue、複製、素材の参照寿命。

- [テキスト編集session](editor-session.md): 入力状態、選択分岐、確定と保存成功の境界。

- [視点履歴と編集変更の表示補助](viewport-history.md): cameraの状態所有者、履歴境界、Undo/Redoの変更位置表示。

## 維持する境界

- 利用者の内容は端末内に保存し、表示や選択だけで保存済み内容を変えません。
- 編集の未確定状態、表示の履歴、永続化した内容を区別します。確定・取消・保存失敗と再試行の
  操作契約は該当する Living Spec とテストを参照します。
- Compose、Room 3、Jetpack Ink、Android 標準APIを優先します。現在の依存版・SDK値は
  [app設定](../../app/build.gradle.kts)、[root設定](../../build.gradle.kts) と
  [Gradle Wrapper](../../gradle/wrapper/gradle-wrapper.properties)が根拠です。
- IMEの未解明事象と隔離は [専用runbook](../runbooks/ime-quarantine.md)に従います。
  文書の整理を理由に隔離・テストの合格条件を変えません。

既存specディレクトリ内の設計補助資料には、現在も必要な詳細契約が残っています。
[文書の責務](../runbooks/change-review.md#文書の責務)に従い、個々の内容を照合して抽出するまでは
保持します。この概要だけで詳細契約の移行が完了したとは扱いません。
