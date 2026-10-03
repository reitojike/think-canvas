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

レビュー指摘を受けた bounded correction は原則 **2 round まで**です。ここでいう round は
review request、commit、finding の件数ではなく、material finding または同じ semantic family に
対して実装範囲を固定し、その範囲を一度補正して再検証する単位です。correction count を回避する
ために finding を細分化したり、review request を繰り返したりしてはいけません。

3 round 目の補正が必要になった時点で、実装を続ける前に必ず `HOLD` し、family-level
completeness checkpoint を行います。2 round 未満でも、同じ semantic family の指摘が繰り返される
場合は早めに切り替えてかまいません。family は同じ authority または contract を共有する問題群です。
例として、描画座標、semantics と操作可否、保存と retry の lifecycle、gesture の競合があります。

checkpoint では、関連する画面、モデル、保存、テストなどの surface を有限集合として列挙し、
個別 finding の修正ではなく family 全体の completeness と、現在の design authority が維持されて
いるかを read-only で確認します。その結果から、correction 回数ではなく
**responsibility / authority / review boundary / rollbackability** を基準に、次のいずれかを選びます。

- `BOUNDED_CORRECTION`: 同じ authority と responsibility のまま、有限な surface に閉じ、
  scope drift がなく、同じ PR で扱う方が review と rollback の境界が明瞭な場合です。
  checkpoint で correction scope を明示してから、**同じ PR を bounded に再開してよい**ものとします。
  これは correction ceiling の解除ではありません。新しい material family や scope drift が出たら
  再び `HOLD` します。
- `FOLLOW_UP`: 現在の PR が本来の contract を満たしたまま切り離せる別 responsibility で、
  独立した delivery value、owner、review boundary、または rollback boundary を持つ場合です。
  follow-up Issue / PR に分離し、現在の PR に抱え込ませません。
- `REBUILD_REQUIRED`: design / architecture authority や responsibility の置き方そのものが崩れ、
  同じ PR への追加補正では reviewability や rollbackability を保てない場合です。現在の PR は
  evidence / reference として freeze し、設計を決め直して clean baseline から別 PR で作り直します。
- `NO_CHANGE`: finding が成立しない、既に満たされている、または code / docs change が不要な場合です。

**「3回目だから」という理由だけで新しい Issue、branch、stacked PR を必須にしてはいけません。**
逆に、同じ PR で直せるという理由だけで別 responsibility や design rebuild を押し込んでも
いけません。carrier の分離は correction number ではなく、上記の responsibility と境界で決めます。

correction / checkpoint 中に canonical review を round ごとに反復することは原則としません。
補正が収束した final candidate head を freeze し、その head の required CI を確認してから、
最終 gate として canonical review を依頼します。final review 後に head が変わった場合は、
古い CI / review evidence を再利用しません。CI failure も green を引くために blind rerun せず、
原因を分類して Task Contract とこの checkpoint rule に従います。
