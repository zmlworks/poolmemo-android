# PoolMemo (Android)

PoolSketch 上の暗号化パーソナルメモ。パッケージ `com.zmlworks.poolmemo`。

共通仕様・暗号フォーマットは `/workspace/poolmemo/README.md` を参照。

## ビルド

```sh
. /workspace/android/env.sh
cd /workspace/android/pool-memo
./gradlew :app:assembleRelease
```

署名は `/workspace/android/keys/keystore.properties`（リポジトリ外）。

成果物: `app/build/outputs/apk/release/` → 配布用コピー `/workspace/android/out/pool-memo-0.3.0-release.apk`

## 同期（v0.3.0）

`SyncEngine`（取得→3-way マージ→書込み→再取得確認）、`Merge3`（行単位 diff3、同一行の競合は両方残してマーカー行）、
`LocalStore`（ベース版と未送信下書きをアプリ専用領域に暗号化保存）。詳細は `/workspace/poolmemo/README.md`「同時編集対策」。

テスト: `./gradlew --offline :app:testReleaseUnitTest`
