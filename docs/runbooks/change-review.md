# 変更とレビューの案内

作業開始時に、Issue と依頼内容から **Task Contract** の完了境界を確かめます。

| 完了境界 | この作業で到達する地点 |
| --- | --- |
| read-only / report-and-stop | 調査結果を報告して停止する。変更や PR は作らない。 |
| PR 作成 | 検証して PR を作成した時点で停止する。 |
| `MERGE_READY` | PR 作成後も CI とレビューの収束まで続け、merge 前に停止する。 |
| merge 後の Issue 完了 | merge が許可された場合に限り merge し、別途 Issue の完了条件を確認する。 |

後段の手順に書かれた操作は、元の Task Contract の停止条件を広げません。依頼内容、Issue、
既存の許可が食い違う場合は、利用者の指示を優先して境界を確認します。

1. [PR 前の検証](pre-pr-verification.md): 変更範囲、ローカルの機械的検証、公開情報境界を確認する。
2. [PR 後の収束](post-pr-convergence.md): Task Contract が求める場合、現行 PR head の CI とレビューを確認して `MERGE_READY` を判定する。
3. [merge 後の Issue 完了](post-merge-issue-closure.md): merge 権限と Issue 完了権限がある場合、最新の受け入れ条件を個別に確認する。

Android の実機配布と確認方法は [Android 実機確認](device-verification.md) を参照します。
機械的な CI の実行内容は [Android checks](../../.github/workflows/android.yml) を確認します。
## 文書の責務

- **現行仕様**: `specs/**/spec.md` は対象機能の Living Spec です。現在の振る舞い・境界・
  受け入れ条件を記載し、変更時には同じ仕様を更新します。番号は既存リンクの維持用であり、
  新しいタスクのたびに続番を作る規則ではありません。
- **現在の設計**: `docs/architecture/` に構成・責務・永続データ・技術上の判断を置きます。
- **現在の運用**: `docs/runbooks/` に繰り返し使う検証・配布・運用の手順を置きます。
- **変更の記録**: Issue / PR に目的・範囲・対象外・作業計画・検証結果・進捗・判断と停止条件を
  残します。PR では変更した現行仕様と設計を参照し、リポジトリに作業日誌を追加しません。
- **機械的な契約**: 実装・schema・テスト・CI が実行時の根拠です。仕様との衝突は変更前に
  解消し、古い文書を一律に優先したり、実装済みという理由だけで未承認の挙動を正当化しません。

既存 `specs` の plan / research / data-model / contracts / quickstart 等は、過去の計画と
現在も必要な契約が混在しています。ファイル名だけで現行の正本とも廃棄可能な履歴とも判断
しません。該当部分を読む際は現行 spec・実装・Issue / PR と照合し、必要な契約を抽出して
参照を付け替えるまでは保持します。tasks / checklist のチェック状態を現在の完了証拠とせず、
最新 Issue / PR の証拠を確認します。未分類資料の整理は #205 で追跡します。
