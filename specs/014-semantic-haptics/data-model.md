# 状態と保存境界

新規データモデルなし。feedbackは既存gesture成立または作成/変更確定の副作用に限る。BoardSnapshot、Room schema、save acknowledgement、Undo/Redo、viewport historyへ格納しない。テストrecorderはCompositionLocalに限り、本番public APIに注入点を足さない。
