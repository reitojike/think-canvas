# PR 後の収束

Task Contract が `MERGE_READY` までの継続を求める場合に使います。PR 作成は中間地点です。
read-only、報告後停止、PR 作成後停止の指示を、この手順で上書きしてはいけません。
この手順の到達点は `MERGE_READY` であり、merge と Issue close は含みません。

## 判定条件

1. PR の base は `main` とし、現在の base と head の SHA、GitHub の mergeability を取得します。
   base の鮮度が不明、または branch が behind のときは `HOLD` とします。base 更新で head が
   変われば、新しい head について検証をやり直します。
2. [Android checks](../../.github/workflows/android.yml) と repository settings で現に必須の
   CI を確認します。現行 PR head の lint・単体テスト・build・公開境界・emulator の結果が
   成功していることを確認します。対象 head 以外の成功結果は使いません。
3. **GitHub の PR 上**に top-level の `@codex review` を依頼し、現行 head に対する結果を
   canonical review evidence とします。GitHub の `APPROVED` 表示自体は必須ではありません。
   finding のない結果、または指摘の解決が確認できる結果を使います。同じ head で再度レビューを
   依頼した場合も、その依頼より前の clean result は最終証跡にしません。依頼と結果の時刻が
   同じ、解析できない、または head との対応が不明なら `HOLD` とします。
4. 実装担当と異なる provider による semantic second opinion は、変更リスクに応じて追加します。
   Codex 実装では Claude Review、Claude 実装では canonical Codex Review が候補です。データ消失、
   privacy、端末内保存、migration、release・署名、レビュー手順自体の変更では特に推奨します。
   通常の PR では、別 provider の利用可否だけを必須の機械的 gate にしません。GitHub Copilot と
   CodeRabbit の指摘は参考にできますが、canonical review の代わりにはしません。
5. outdated を含む review thread を全件確認します。修正済み、適用外、古くなった thread も
   理由を判断して GitHub 上で resolve し、未解決を 0 件にします。レビュー後に head が
   変われば、古い CI とレビュー結果を再利用せず、現行 head で再依頼します。

CI、review、base、thread の証跡が missing、pending、unknown、stale、failed のいずれか、
または API で取得できない場合は `HOLD` です。現行 head の必須 CI が green、現行 head で
最後の依頼より新しい canonical review evidence があり、base が最新、未解決 thread が 0 件の
場合に限り `MERGE_READY` と報告します。

## 補正の上限

レビュー指摘を受けた bounded correction は原則 2 回までです。3 回目が必要になった時点で
`HOLD` にし、指摘を一件ずつ直し続けません。2 回未満でも、同じ semantic family の指摘が
繰り返される場合は早めに切り替えます。family は同じ authority または contract を共有する
問題群です。例として、描画座標、semantics と操作可否、保存と retry の lifecycle、gesture の
競合があります。

切り替え時は、関連する画面、モデル、保存、テストなどの surface を有限集合として列挙し、
family 全体の completeness を read-only で確認します。結果を PR に記録し、
`BOUNDED_CORRECTION`、`FOLLOW_UP`、`REBUILD_REQUIRED`、`NO_CHANGE` のいずれかを判断して
から実装範囲を固定します。finding の分割やレビュー依頼の反復で補正回数を回避しません。
