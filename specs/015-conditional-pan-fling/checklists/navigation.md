# 表示移動の要件レビュー

対象: [spec](../spec.md)。Created: 2026-10-06。
このcustom checklistは要件品質のreviewer-owned artifactであり、実装完了を表さない。

- [ ] CHK001 低速の即停止と速い払いの条件が区別されているか。[Clarity, FR-001/002]
- [ ] CHK002 正常release/取消/他gestureの境界が有限か。[Coverage, FR-002]
- [ ] CHK003 全割り込みとstale frameの停止責任が明確か。[Completeness, FR-003]
- [ ] CHK004 自然/途中停止の履歴境界が一貫するか。[Consistency, FR-004]
- [ ] CHK005 content/save/edge/accessibilityへの非影響を定義したか。[Coverage, FR-005]
- [ ] CHK006 自動検証と実機比較の完了条件を分けたか。[Measurability, FR-007]

利用者は条件付き慣性の実装を依頼し、低速即停止・払い継続の方針を指定済み。実装を進めるauthorizationはこの依頼に基づく。markerはreviewer所有のまま保持し、追加確認を重複させない。
