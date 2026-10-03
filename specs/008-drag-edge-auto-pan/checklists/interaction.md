# Interaction要件チェックリスト: edge auto-pan

**目的**: moveとviewportの責務、取消、mobile UXの要件品質をPR reviewerが確認する。
**作成日**: 2026-10-03
**対象**: [spec.md](../spec.md)、[plan.md](../plan.md)

このcustom checklistはreviewer-owned。`[x]`は要件品質の査読済みを示し、実装完了を意味しない。生成時は未チェックとし、implementはmarkerを変更しない。

## 範囲と明確さ

- [ ] CHK001 対象move familyと除外handle/toolが有限集合で明記されているか？ [Completeness, Spec FR-001/008]
- [ ] CHK002 「端に近いほど速い」の単調性・上限・停止帯とprototype調整値が区別されているか？ [Clarity, Spec FR-003]
- [ ] CHK003 静止pointerの保持と、中央復帰後の通常drag継続が矛盾なく定義されているか？ [Consistency, US1]

## 保存と終了

- [ ] CHK004 pointer/cameraの二重加算なし、preview/commit一致、相対配置の受け入れ条件が測定できるか？ [Measurability, FR-004/005, SC-002]
- [ ] CHK005 一回のmoveとUndo/save、viewport非contentが明記されているか？ [Completeness, FR-006]
- [ ] CHK006 全取消/handoff/recreation/保存制限後の残留と古いUPの契約が一致しているか？ [Coverage, FR-007, contracts]

## 依存とUX

- [ ] CHK007 #73のWIPとmerge済みauthority、実装開始時の確認が区別されているか？ [Dependency, Assumptions]
- [ ] CHK008 pointer機能が既存accessibilityを置き換えず、bounded emulator UXと物理端末の横断UXを区別しているか？ [Coverage, FR-009/010]

## Notes

標準深度、PR reviewer向け。利用者は#81の実装とmergeを依頼済みで、要件上のcriticalな未決事項はない。custom markerをauthorが実装完了扱いにしない。
