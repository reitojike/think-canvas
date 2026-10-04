# 要件品質Checklist: 共有取り込みlifecycle

**Purpose**: PR reviewerが保存/復元/取消/操作保護の要件を評価。
**Created**: 2026-10-04
**Feature**: [spec.md](../spec.md)
**Review Ownership**: reviewer-owned。`[x]`は要件品質の確認であり実装完了ではない。生成時全項目未チェック。

## 完全性・明確さ

- [ ] CHK001 warm/cold候補順と候補なし/変更が一意に定義されているか。[Completeness, Spec FR-002/003]
- [ ] CHK002 確定前cancel、explicit task終了B、OS復元の違いが明確か。[Clarity, Spec FR-006/011]
- [ ] CHK003 同要求復元と同文別共有を区別するidentity要件が明確か。[Clarity, Spec FR-008/012]

## 整合性・観測可能性

- [ ] CHK004 一Undo/一saveとFailed/manual retryが矛盾しないか。[Consistency, Spec FR-005/010]
- [ ] CHK005 UI ack欠落・完了後Undo/削除で旧要求を再適用しない境界が明示されているか。[Consistency, Spec FR-009, SC-003]
- [ ] CHK006 保留で既存操作を守り、確定はlive再判定する条件が明確か。[Measurability, Spec FR-007, US3]

## 例外と非機能

- [ ] CHK007 不正/対象外/空/busy二件目/destination消失が欠落していないか。[Coverage, Spec FR-015, Edge Cases]
- [ ] CHK008 privacy/自動URL取得なしが全受信/復元経路に共通か。[Consistency, Spec FR-013]
- [ ] CHK009 全文/選択先/確定/取消/失敗の読み上げ要件が明確か。[Completeness, Spec FR-014]
- [ ] CHK010 自動再配置なしと既存保存/編集/視点authorityの範囲が明確か。[Dependencies, Spec FR-004/005, Assumptions]

## Notes

実装チェックへ読み替えない。A/A・Bを再質問するgateではない。
