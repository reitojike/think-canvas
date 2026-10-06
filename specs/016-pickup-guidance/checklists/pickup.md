# 要件のレビュー

利用者は推奨pickup-firstとAndroid標準優先を選択し、実装を承認済み。以下はreview担当の品質確認。

- [ ] CHK001 全対象の成立/release/dragの意味が明確か [spec FR-001/003]
- [ ] CHK002 標準との差、限定理由、accessibilityを記録したか [plan]
- [ ] CHK003 held/終了/取消/別操作の寿命に漏れがないか [spec FR-004]
- [ ] CHK004 画像selection/resource不変条件が明確か [spec FR-002/005]
- [ ] CHK005 通常guidanceとの優先順位に矛盾がないか [contracts/pickup]
- [ ] CHK006 実機理解しやすさと機械gateを区別したか [spec SC-004]
