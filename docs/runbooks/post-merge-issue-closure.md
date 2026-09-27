# merge 後の Issue 完了

この手順は PR の収束とは別の phase です。Task Contract が merge と Issue 完了を許可し、
対象が単一の完了可能な Issue の場合に限り使います。`MERGE_READY` のみを求める作業、
read-only の作業、親・roadmap・tracking・coordination Issue、close 禁止の作業では停止します。
適用可否が不明なら `HOLD` とします。

1. PR が実際に merge されたこと、merge commit の SHA、必要なら main の CI 結果を確認します。
   PR の merge だけで Issue の完了を推定しません。
2. Issue の**最新本文**を再取得し、Acceptance Criteria を 1 件ずつ、merge 済み成果物と
   検証結果に照らして意味上判定します。実際に達成した checkbox だけを更新します。
   未達、未確認、deferred、scope 外の条件はチェックしません。他者による本文変更があれば
   最新の内容から判断し直します。
3. 既知の残作業、必要な実機確認、関連する子 Issue、親・追跡 Issue としての役割を確認します。
   未チェックの条件、既知の残作業、完了権限の不足があれば close せず、残る作業を記録します。
4. 完了可能な場合は、実装 PR、merge commit、各条件の検証、現行 head のレビュー、未解決
   thread が 0 件であることを Issue に記録します。記録後に最新の本文と状態を再確認し、
   すべて満たす場合だけ `completed` で close します。API や証跡が不明なら `HOLD` とします。

Issue 本文の更新は、直前に取得した最新内容を基に、該当する checkbox のみ変更します。
競合する編集が疑われる場合は書き込みを止め、再取得して判定します。
