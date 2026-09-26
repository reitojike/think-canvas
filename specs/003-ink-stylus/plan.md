# 実装計画: Ink & Stylus

**Branch**: `codex/issue-4-ink` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

## Summary

既存の Compose キャンバスの入力判定へ線の入力を加える。Jetpack Ink の Stroke/Brush とレンダラーで描き、確定した入力を世界座標のまま Room 3 に保存する。同種の連続線は一つの要素にまとめ、BoardSnapshot の履歴は線1本ごとに記録する。

## Technical Context

**Language/Version**: Kotlin、JDK 25 でビルド、bytecode 17
**Primary Dependencies**: Compose、Jetpack Ink 1.0.0 stable
**Storage**: Room 3 の既存 `thinkcanvas.db` に追加する ink tables
**Testing**: JUnit、Room 3 JVM テスト、Android lint、エミュレーター
**Target Platform**: Android 26–37
**Project Type**: Android アプリ
**Performance Goals**: 描画中の線をフレームごとに更新し、確定後の画面切り替わりで線が消えない
**Constraints**: オフライン、既存データ維持、非公開資料はコミットしない
**Scale/Scope**: 単一ボード、2種類の固定ブラシ

## Constitution Check

- **製品 authority**: 非公開 PRD の線種、1.5秒、スタイラス優先、Undo 単位を spec に抽出した。HTML モックは配置と色の参照に限る。
- **Standard-first**: Jetpack Ink の Brush/Stroke/レンダラーを使う。指1本で描く操作は製品要件上のモード内だけに限定し、2本指操作と TalkBack の操作経路を残す。
- **空間配置**: 入力を確定時に世界座標へ変換して保存する。viewport は保存座標を変更しない。
- **ローカル優先**: 線は既存の Room 3 DB に保存し、ネットワークは使わない。
- **仕様先行**: spec のシナリオと clarifications を確認済み。実装とテストは tasks に従う。
- **公開境界**: PRD/HTML モック、端末固有のパスと鍵は追加しない。

## 設計判断

- Ink 1.0.0 stable を採用する。1.1.0 系は alpha のため、最新安定版優先の規則に従う。
- 既存の gesture 処理と選択規則を保つため、pointer input の判定を一箇所で行い、Ink の Stroke モデルと描画レンダラーを組み込む。指2本へ変わったら未確定線を破棄する。
- 保存は追加テーブルを使い、schema v1→v2 の migration を定義する。既存の文字・図形・矢印を維持する。まとまりの各線を独立した行で持つことで1本ずつの Undo に対応する。
- マーカーの描画レイヤーを文字の背後、ペンを文字の前面に置く。モックと同じペン/マーカー/やめる UI を画面上部に出す。
- スタイラス固有の物理的な書き味と振動はエミュレーターで確証できないため、実機確認を後続の検証記録に残す。

## Project Structure

```text
app/src/main/java/com/thinkcanvas/canvas/
  CanvasScreen.kt          入力、操作、UI
  InkElement.kt            線のモデルとグループ判定
  InkLayer.kt              Ink 描画と選択表示
  BoardState.kt            履歴と編集
  SpatialGeometry.kt       範囲選択と移動
app/src/main/java/com/thinkcanvas/data/
  CanvasDatabase.kt        schema/migration
  CanvasStore.kt           読み書き
app/src/test/java/com/thinkcanvas/
  canvas/                  座標・グループ・Undo
  data/                    保存と migration
specs/003-ink-stylus/      仕様と検証
```

**構成の判断**: 既存の単一 app module を拡張する。新しいサービスやリポジトリ層は作らない。

## Complexity Tracking

標準からの例外は、ペンモード中の指1本を描画に割り当てる操作のみ。スタイラスや指2本の標準的な操作を維持し、TalkBack からの選択・移動・削除を提供する。
