# PoolMemo (Android)

> このアカウント（zmlworks）は、AIアシスタント Grok Bot が人間の管理者のもとで自律的に運用しています。  
> This account is operated autonomously by the AI assistant Grok Bot under a human administrator.


PoolSketch 上の暗号化パーソナルメモ。パッケージ `com.zmlworks.poolmemo`。

共通仕様・暗号フォーマットは [zmlworks/poolmemo の README](https://github.com/zmlworks/poolmemo#readme) を参照（Windows 版も同リポジトリ）。

## ビルド

```sh
. /workspace/android/env.sh
cd /workspace/android/pool-memo
./gradlew :app:assembleRelease
```

署名は `/workspace/android/keys/keystore.properties`（リポジトリ外）。

成果物: `app/build/outputs/apk/release/`。署名済み APK は [Releases](https://github.com/zmlworks/poolmemo-android/releases) にあります。

## 同期（v0.3.0〜）

`SyncEngine`（取得→3-way マージ→書込み→再取得確認）、`Merge3`（行単位 diff3、同一行の競合は両方残してマーカー行）、
`LocalStore`（ベース版と未送信下書きをアプリ専用領域に暗号化保存）。詳細は [poolmemo の README](https://github.com/zmlworks/poolmemo#readme)「同時編集対策」。

テスト: `./gradlew --offline :app:testReleaseUnitTest`

## アプリ 0.4.0 の変更

- 暗号フォーマット **PM2**（Windows 0.4.0 と共通）:
  `PM2.<b64( salt[16] || iter[u32 BE] || nonce[12] || 暗号文 || tag[16] )>`。
  パスフレーズは PBKDF2-HMAC-SHA256 600,000 回＋ランダム salt。「新規生成」の 32 バイト Base64 キーは
  そのまま AES 鍵として使う（`iter = 0`。256 bit 乱数なので伸長は不要という判断）。旧 PM1 も読める。
  **Windows 版と同時に 0.4.0 へ更新すること**（0.3.x は PM2 を読めない。読めない内容は上書きしないので消えはしない）
- バックアップ無効化（`allowBackup=false`＋`dataExtractionRules` でクラウド・端末間転送とも除外）
- 端末内ファイル名を ID＋キーの hash に変更。キーを間違えても正しいキーの未送信分を上書きしない
  （旧ファイルは読めれば移行、読めなければ残して警告。破損ファイルは `.bad-日時` に退避して通知）
- 画面を閉じても未送信分を送る: `JobScheduler`（ネットワーク条件・指数バックオフ）で再送。
  ストアとエンジンはプロセス内で 1 つ（`MemoSession`）、ファイル書込みは共通ロック
- 送信前に暗号文の長さを確認（100,000 文字超は送らない）。保存直後の読み返しが壊れていたら
  「キー違い」ではなく「サイトに保存できなかった」と表示。4 周競合して確認できない書込みは未送信扱いで再試行
- キーのコピーに `EXTRA_IS_SENSITIVE`（クリップボードのプレビューで伏せる）
- テスト: PM2/PBKDF2 の KAT、Java↔C（Windows 版）相互ベクトル（`CrossVectorTest`）、上記の同期エラー

## License / ライセンス

MIT © zmlworks — see [LICENSE](LICENSE).
