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

## 同期（v0.3.0）

`SyncEngine`（取得→3-way マージ→書込み→再取得確認）、`Merge3`（行単位 diff3、同一行の競合は両方残してマーカー行）、
`LocalStore`（ベース版と未送信下書きをアプリ専用領域に暗号化保存）。詳細は [poolmemo の README](https://github.com/zmlworks/poolmemo#readme)「同時編集対策」。

テスト: `./gradlew --offline :app:testReleaseUnitTest`

## License / ライセンス

MIT © zmlworks — see [LICENSE](LICENSE).
