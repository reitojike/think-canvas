# Navigation UX Checklist: 画面外対象の方向表示

**Purpose**: PR reviewerによる要件品質の照合
**Created**: 2026-10-04
**Feature**: [spec.md](../spec.md)

**Review Ownership**: reviewer-owned。`[x]`は要件品質へのreviewer承認であり実装完了ではない。implementはmarkerを変更しない。

## 対象と判定

- [ ] CHK001 2対象・単一重複・最大数の範囲は一義的か？ [FR-001]
- [ ] CHK002 完全offscreen/部分可視/巨大または疎なgroupの規則は明記されているか？ [FR-002, Edge Cases]
- [ ] CHK003 安全な配置、衝突、省略順、48dpは測定可能か？ [FR-003]

## 操作と寿命

- [ ] CHK004 視点authorityと検索/選択/保存/履歴不変は整合するか？ [FR-004/005]
- [ ] CHK005 pan/zoom/対象変更/終了時の古いhitの扱いは網羅されているか？ [FR-006]
- [ ] CHK006 入力・保存・move/auto-panとの抑止境界は有限で明確か？ [FR-007]

## 可視性とaccessibility

- [ ] CHK007 Semantic Zoomの境界とperformance制約は既存契約と整合するか？ [FR-008]
- [ ] CHK008 対象識別、action、複数の安定順と機械的/実機確認の範囲は明記されているか？ [FR-009/010, Assumptions]

## Notes

POは2対象で進めることと#81対応/mergeを承認済み。追加の製品判断が必要なら停止する。markerは未査読のまま保持する。
