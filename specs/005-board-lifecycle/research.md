# 採用判断: Board Lifecycle

## 既存ボードの保存

**判断**: Room 3 の既存 schema v2 を維持する。`boards` と4種の要素表に既に `boardId` があるため、DAO と Store の ID 1 固定を解消し、ボード ID 指定の読込・保存・削除にする。既存 id=1 のボードと要素は更新後も同じ行を使う。`lastOpenedBoardId` と初回案内の完了は端末内設定に保持する。

**理由**: schema の再作成や自動消去を避け、既存データの移行リスクを抑える。複数ボードでも Room 3 のトランザクションで対象ボードの要素をまとめて更新できる。[Room の関係](https://developer.android.com/training/data-storage/room/relationships/one-to-many)、[Room 3 の transaction](https://developer.android.com/reference/kotlin/androidx/room3/Transaction)。

**検討した代案**: `app_state` 表を追加して schema v3 に上げる案は、最後に開いた ID と案内状態を DB 内に置けるが、現時点では新たな移行を必要とする。4種の要素表を foreign key 付きで再作成する案も、今回の範囲に対して移行リスクが大きい。

## 保存と切替の競合

**判断**: `save(boardId, snapshot)` は呼び出し時に ID と変更後の snapshot を固定する。Store 内で同じ順序列に保存・複製・削除を通し、対象ボードが存在しない保存で暗黙に再作成しない。画面上は編集中または保存中の一覧遷移を保留し、失敗時は編集状態を保持する。

**理由**: 既存の保存待ち coroutine は画面破棄でキャンセルされても Store の保存自体が続く。ID を明示し、Store の操作順序を保証すれば別ボードの上書きと削除後の復活を防げる。[Room 3 の transaction](https://developer.android.com/reference/kotlin/androidx/room3/Transaction)。

**検討した代案**: UI の `saving` フラグだけに頼る案は、画面破棄や複製・削除との競合を防ぎきれない。

## 複製と Undo/Redo

**判断**: 複製ではボードと全要素に新しい識別子を割り当て、矢印の接続先を複製側の識別子へ写す。手書きの線も別識別子にする。ボードを開くたびに別の `BoardState` を作り、Undo/Redo はその編集セッションに限る。削除済みボードの履歴は破棄する。

**理由**: 既存の要素表は要素 ID を主キーとするため、ID を流用すると元ボードの要素を上書きする。履歴をボード間で混ぜないことで誤操作を防ぐ。

**検討した代案**: 元の ID を複製へ流用する案と履歴をボード間で共有する案は、保存の独立性を満たさない。

## プレビューと画像出力

**判断**: 一覧カードは Spec 004 の遠景表示を使う。共有シートのプレビューと PNG は同じ対象要素と描画規則を使い、本文・手書き・矢印を省略しない。world 座標の外接範囲へ均等な余白と不透明な `#FCFCFB` 背景を付ける。最大長辺 4096 px、総画素 16 MP の両方を上限とし、縦横比を保って縮小する。空範囲、非有限座標、過大な計算値は出力を拒否する。

**理由**: 現行画面のキャプチャには viewport と操作 UI が混ざり、全体/範囲出力を正しく表せない。出力対象を固定した描画面を別に作ると、プレビューと PNG が一致する。寸法上限は Android が保証する固定値ではなく、この製品のメモリ管理上の値である。[Compose の描画](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)。

**検討した代案**: 画面全体の screenshot は範囲・解像度・操作 UI を制御できない。遠景の簡略表示を共有画像にも適用すると本文や Ink が欠ける。

## Android の画像受け渡し

**判断**: PNG の保存は API 29 以上で `MediaStore.Images`、API 26–28 で system の文書作成画面を使う。コピーと Android 共有には限定した cache 領域の PNG を `FileProvider` の content URI で渡す。共有先の選択を利用者へ委ね、cache は直後に消さず期限を設ける。

**理由**: Android の標準フローを使い、広いストレージ権限や公開 file URI を避ける。保存は書込成功後に公開し、失敗時は不完全な出力を削除する。[共有メディア](https://developer.android.com/training/data-storage/shared/media)、[文書作成](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.CreateDocument)、[安全なファイル共有](https://developer.android.com/training/secure-file-sharing)、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)、[コピーと貼り付け](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)。

**検討した代案**: 外部ストレージの広い権限と `file://` URI は不要で、受け手への権限付与も難しい。画像の自動アップロードは製品要件に含まれない。
