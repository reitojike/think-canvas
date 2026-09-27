# 要件レビュー: Board Lifecycle と画像共有

**目的**: 保存、削除、複製、画像共有の要件が実装前に十分具体的かをレビューする。
**作成日**: 2026-09-27
**対象**: [spec.md](../spec.md)、[操作契約](../contracts/interaction.md)

この checklist は要件の品質を確認するためのものです。`[x]` は reviewer が要件を確認した印で、実装完了を意味しません。

## ボードのライフサイクル

- [ ] CHK001 一覧順と同時刻の順序、日付表現は一意に判定できる形で定義されているか。 [Clarity, Spec FR-001]
- [ ] CHK002 新規作成、再開、名前変更、複製、削除の対象と結果はそれぞれ独立に判定できるか。 [Completeness, Spec FR-002–006]
- [ ] CHK003 空名、同名、0件、最後のボード削除後の状態は矛盾なく定義されているか。 [Coverage, Spec Edge Cases]
- [ ] CHK004 保存中・失敗中の切替や削除に対して、変更を失わない要件が定義されているか。 [Coverage, Spec Edge Cases]
- [ ] CHK005 既存の単一ボード更新時のデータ保持と失敗時の扱いは測定可能か。 [Measurability, Spec FR-007]
- [ ] CHK006 複製後の要素、接続関係、Undo/Redo 履歴の独立性は明確か。 [Consistency, Spec FR-004–005]

## 案内と操作

- [ ] CHK007 初回案内を表示する条件と閉じた後の再表示は、空ボード・既存ボードの両方で定義されているか。 [Clarity, Spec FR-008–009]
- [ ] CHK008 一覧、下部メニュー、削除確認、案内、共有の支援技術向け要件は網羅されているか。 [Completeness, Spec FR-014]

## 画像の対象と受け渡し

- [ ] CHK009 ボード全体と選択範囲、囲み内、矢印の包含規則は一意に判定できるか。 [Clarity, Spec FR-010]
- [ ] CHK010 一覧の遠景プレビューと共有用の詳細画像の違いは明確か。 [Consistency, Spec FR-001・FR-011]
- [ ] CHK011 背景、余白、読める大きさ、上限の条件は出力画像を比較できる程度に定義されているか。 [Measurability, Spec FR-012]
- [ ] CHK012 保存、コピー、Android 共有の成功・キャンセル・失敗時の結果と元データ不変性は定義されているか。 [Coverage, Spec FR-011–013]
- [ ] CHK013 空対象と異常な座標、画像化できない大きさの扱いは要件または plan で定義されているか。 [Edge Case, Spec Edge Cases]

## 補足

この checklist の marker は reviewer が判断するまで変更しない。実装作業は [tasks.md](../tasks.md) で管理する。
