# 状態モデル

- 選択集合: 既存`Set<String>`。0/1件は通常primary action、2件以上は追加/解除。
- TextPrimaryAction: EDIT / ADD_TO_SELECTION / REMOVE_FROM_SELECTION。判定は入力集合を変更しない。
- EDITは既存Draft/sessionを1回開始する。明示menu編集の既存契約は維持する。
- ADD/REMOVEは選択集合と必要な選択表示だけを変え、Draft・board・保存・Undoを変えない。
- Draft/save/acknowledgement・active gesture ownerによるguardが先行する。保存schema変更なし。
