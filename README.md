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

成果物: `app/build/outputs/apk/release/` → 配布用コピー `/workspace/android/out/pool-memo-0.2.2-release.apk`
