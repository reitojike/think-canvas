# 実装計画: Canvas Foundation

**Branch**: `001-canvas-foundation` | **日付**: 2026-09-26 | **仕様**: [spec.md](spec.md)

## 概要

Compose の単一画面で世界座標を保持するキャンバス、テキスト入力、パン・ズーム、選択・移動、Undo/Redo を構築する。最初のボードと確定済み要素は Room 3 に保存する。表示領域と操作履歴はセッション内に保持する。

## 技術コンテキスト

- **言語**: AGP 9.4.0 組み込み Kotlin、Compose compiler plugin 2.4.20、JVM 17
- **主な依存**: Jetpack Compose BOM 2026.09.00、Activity Compose、Room 3.0.3、KSP 2.3.12
- **保存**: Room 3 / SQLite。Board 1 件と TextElement、schema v1
- **検証**: JUnit による座標変換と履歴の単体テスト、Android 上での Room と操作シナリオ確認、GitHub Actions で lint・テスト・ビルド・公開情報境界チェック
- **対象**: Android API 26 以上、compile API 36、target API 36。Gradle 9.7.1。ビルド基盤は Issue #10 で更新。Android 17 SDK は Preview のため採用を保留
- **性能目標**: 100 テキスト要素でパン・ズーム時の操作が追従し、確定後の再起動で欠損しない
- **制約**: オフライン、AI なし、既存の単一 app module を維持

## Constitution 照合

| 原則 | 判定と対応 |
|---|---|
| PRD の authority | 仕様は PRD と Issue #2 に基づく。HTML モックを対象コンポーネントの見た目と操作の参考実装として使う。非公開資料は追加しない。 |
| Standard-first | 入力とタッチ操作は Compose の標準 API を使い、見た目はモックに合わせる。キャンバス上の要素起点パンと長押し移動の区別は PRD の空間操作に必要で、専用 pointer input に限定する。操作ボタンと要素に semantics を付ける。 |
| 空間配置 | ワールド座標を保存し、viewport の変換だけで表示する。整列やスナップなし。 |
| ローカル優先 | Room 3 に保存し、ネットワーク権限・AI 依存なし。 |
| 仕様先行 | 本計画と tasks/analyze を実装前に完成させる。 |

Phase 1 の設計後も上記を満たす。例外はキャンバス上の複合 gesture と、モックのインライン入力・小さな操作ピルを再現する外観のみ。入力の編集、focus、IME、semantics は Compose の標準 API を用いる。

## 構成

```text
app/src/main/java/com/reitojike/thinkcanvas/
├── MainActivity.kt
├── canvas/BoardState.kt
├── canvas/Viewport.kt
├── canvas/CanvasScreen.kt
├── data/CanvasDatabase.kt
└── data/CanvasStore.kt
app/src/test/java/com/reitojike/thinkcanvas/canvas/
└── BoardStateTest.kt
app/schemas/
```

単一 module とし、画面状態・座標変換・永続化の責務だけを分ける。将来の BoardEngine や複数ボード用の抽象層は先に作らない。

## 実装判断

- スクリーン座標 `s = w * scale + pan`。ピンチ中心が動かないよう `pan' = centroid - (centroid - pan) * (scale'/scale) + gesturePan` とする。
- ボードの要素は ID をキーに保持。作成・編集・移動の前後を 80 件まで履歴として保持し、Undo/Redo 時にも保存する。
- 変更後は UI に保存中を示し、Room の transaction 完了を待ってから確定操作を閉じる。保存処理は画面の lifecycle より長く生きる store scope に置き、失敗時は未保存を示して同じ snapshot を再試行する。保存中は次の変更操作を受け付けない。
- gesture は 1 箇所で優先順位を決める。2 指は zoom、1 指の短い動きは tap、touch slop を超えた動きは pan、要素上の長押し後の drag は移動とする。移動の取消は開始位置へ戻す。
- gesture がキャンセルされた場合は表示用 preview を必ず破棄し、未確定の移動をモデルに反映しない。
- 要素タップの意味を選択状態で切り替える。空白タップの選択解除を新規作成より優先する。
- 編集はタップ位置のインライン入力とキーボード上の見出し・本文・朱・完了ツールバーを使い、確定時だけ履歴と保存に反映する。空白だけの新規入力は破棄する。
- 選択要素にはモックの朱色枠と移動ハンドルを表示する。長押し gesture を使えない場合の位置調整と編集は accessibility action で提供する。
- ボード名ピル、空ボード案内、Undo/Redo ピル、倍率表示はモックの配置・大きさ・色に合わせる。対象外の検索、FAB、ボード一覧は表示しない。
- GitHub Actions は PR と main push で Android lint、JUnit、debug build、公開情報境界チェックを実行する。
- Room の schema v1 を export し、将来の migration に使う。破壊的 migration はしない。

## リスクと検証

- 複合 gesture の競合は実機で空白・要素起点のパン、長押し移動、ピンチを確認する。
- データ損失は確定→完全終了→再起動、および Undo/Redo 後の再起動で確認する。
- 画面の物理サイズ、フォント倍率、TalkBack で入力と操作ボタンの到達性を確認する。
