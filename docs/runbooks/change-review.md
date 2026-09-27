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
