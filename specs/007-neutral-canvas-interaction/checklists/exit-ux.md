# 終了操作の要件品質チェック

**Created**: 2026-10-03
**Feature**: [spec](../spec.md)
**Review Ownership**: reviewerが要件品質を評価する。チェックは実装完了を意味しない。

- [ ] CHK001 Back/Done/Cancel/outside/IMEの異なる意味が明確か？ [FR-002〜004]
- [ ] CHK002 textの種類・色、空白、regionの空名、元へ戻した入力の変更判定が明確か？ [Edge Cases]
- [ ] CHK003 確認dismissとstale sessionの結果が定義されているか？ [FR-003]
- [ ] CHK004 保存中・失敗・pending ackで許されるexitが明確か？ [FR-004,007]
- [ ] CHK005 creation/lasso/ink/previewの完了・取消・selection維持が矛盾しないか？ [FR-005,006]
- [ ] CHK006 Backの一段階処理とboard移動の境界が定義されているか？ [FR-007]
- [ ] CHK007 focus/bounds/pointer cancellationと再生成の結果が観測可能か？ [FR-008,009]
- [ ] CHK008 compact/large font/TalkBackと実機未確認の境界が明確か？ [FR-010,Assumptions]
