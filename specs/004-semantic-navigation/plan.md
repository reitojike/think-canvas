# 実装計画: Semantic Navigation

**Branch**: `codex/issue-5-semantic-navigation` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

## Summary

既存の世界座標と保存済み要素をそのまま使い、現在の viewport と端末の文字サイズから表示段階と要素ごとの可視性を計算する。描画と当たり判定に同じ表示状態を適用する。検索は現在のボード内で計算し、ズーム・フィット・検索結果への視点移動をアニメーションで行う。Room schema は変更しない。

## Technical Context

**Language/Version**: Kotlin、JDK 25 でビルド、bytecode 17
**Primary Dependencies**: 既存の Jetpack Compose、AndroidX Ink、Room 3。追加ライブラリは不要
**Storage**: 既存 `thinkcanvas.db` を読み取る。表示段階、検索語、viewport は永続化しない
**Testing**: JUnit による表示規則・検索順・座標不変条件、Android 17 エミュレーターで UI と gesture、lint・ビルド
**Target Platform**: Android API 26–37
**Project Type**: 単一 module の Android アプリ
**Performance Goals**: 視点の指操作に追従する表示更新、目標約340msの視点移動、通常規模のボードで入力中に目立つ停止を起こさない
**Constraints**: オフライン、保存データと Undo 履歴を変更しない、PRD と HTML モックは非公開のまま
**Scale/Scope**: 現在の1ボード、文字・囲み・図形・矢印・手書き、検索対象は文字と囲み名

## Constitution Check

- **製品 authority**: [spec.md](spec.md) は非公開 PRD の数値と操作を検証可能な条件に整理した。HTML モックは検索欄・倍率表示・強調色の参考とする。
- **Standard-first**: 一般的な入力欄、フォーカス、前後移動、TalkBack の操作を保つ。ズームによる簡略表示は PRD 固有の要件なので、その範囲に限る。
- **空間配置**: 表示用の投影と viewport だけを更新する。要素の世界座標、関連、保存データ、Undo 履歴は変更しない。
- **ローカル優先**: 検索は端末上の現在の snapshot を走査し、外部サービスや索引の送信を行わない。
- **仕様先行**: 段階境界、隠す条件、検索順、操作の移動先を spec と [contracts/interaction.md](contracts/interaction.md) に定めてから実装する。
- **公開境界**: PRD/HTML モックの本文、ローカルパス、端末情報を追加しない。

設計後も上記の gate に違反しない。Android 標準との相違は、PRD が指定する段階表示とダブルタップの移動先に限定する。

## 設計判断

- `SemanticNavigation.kt` に、本文の見かけサイズ、近・中・遠、名前付き囲みの局所的なまとまり、非表示 ID、検索一致の計算を純粋な関数としてまとめる。密度と文字サイズは UI 境界で渡し、保存モデルに追加しない。
- 端末の文字サイズは `LocalDensity` による `sp`→`dp` 相当の変換を使う。Android 14 以降の非線形な文字サイズ拡大があるため、`fontScale` の単純な掛け算はしない。
- 描画には元の snapshot と可視性判定を渡し、接続矢印の座標計算は元の要素を参照する。当たり判定と TalkBack の要素アクションにも同じ判定を使う。
- `SpatialElements` は遠景の囲みの淡い面と名前、図形・矢印の簡略化を描く。`InkLayer` は小さな手書きとまとまった囲み内の手書きを非表示にする。文字は既存の Compose `Text` に表示段階に応じた行数、サイズ、薄さ、省略、検索強調を適用する。
- 検索一致は text と名前付き region のみを対象にし、保存位置の上端・左端・ID の順で並べる。検索中の状態は Compose の一時状態とし、変更時は先頭に移動する。IME の Enter と前後ボタンは循環する。
- 操作で目標 viewport を算出する関数を分け、ズーム表示、空白ダブルタップ、囲みフィット、検索結果で共有する。Compose のアニメーションを同じ340msの時間軸で進め、直接 gesture が始まったら中断する。端末のアニメーション時間設定に従う。

## Project Structure

```text
specs/004-semantic-navigation/
  spec.md               利用者の振る舞い
  plan.md               本文書
  research.md           採用判断
  data-model.md         一時的な表示・検索状態
  contracts/interaction.md 操作と表示の契約
  quickstart.md         検証手順
  tasks.md              実装順
app/src/main/java/com/thinkcanvas/canvas/
  SemanticNavigation.kt 表示判定、検索、視点の計算
  CanvasScreen.kt        UI、入力、視点移動、検索欄
  SpatialElements.kt     囲み・図形・矢印の段階表示
  InkLayer.kt            手書きの可視性
app/src/test/java/com/thinkcanvas/canvas/
  SemanticNavigationTest.kt 境界値・隠す条件・検索・座標不変
app/src/androidTest/java/com/thinkcanvas/canvas/
  SemanticNavigationTest.kt 操作・表示・再起動
```

**構成の判断**: 既存の app module と画面を拡張する。Room schema、外部検索基盤、新しい永続化層は作らない。

## Complexity Tracking

新しい framework や保存 schema は不要。PRD が要求する semantic zoom は通常の一律拡大縮小より複雑だが、表示用の純粋な計算を一箇所へ集め、描画と当たり判定で共用する。
