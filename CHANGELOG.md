# Changelog

## 26.10.1
### Features Added
- Unscheduled the Gloas (Glamsterdam) fork for the Hoodi network, which was previously scheduled at epoch 132352 (26 Oct 2026 17:42:48 UTC). Teku libraries updated to 26.10.2. Required update for the Hoodi network.

### Bugs Fixed

### Breaking Changes

### CI/Build Enhancements

### Security


---
## 26.10.0
### Features Added
- Teku libraries updated to 26.10.1, which schedules the Gloas (Glamsterdam) fork for the Hoodi network at epoch 132352 (26 Oct 2026 17:42:48 UTC). Required update for the Hoodi Glamsterdam fork.

### Bugs Fixed

### Breaking Changes

### CI/Build Enhancements
- Gradle wrapper upgraded to 9.8.1 and Gradle build plugins updated. [#1257][PR_1257]
- Project version calculation needs the `git` executable on `PATH`; without it the version is `UNKNOWN+develop`. [#1257][PR_1257]

### Security


---
## 26.9.0
### Features Added
- Scheduled the Glamsterdam (Gloas) upgrade on Sepolia for epoch 353024 (October 6, 2026 13:53:36 UTC). [#1240][PR_1240]
- Signing support for the upcoming Glamsterdam (GLOAS / ePBS) fork, matching [remote-signing-api v1.4.0](https://github.com/ethereum/remote-signing-api/releases/tag/v1.4.0). [#1192][PR_1192]
- Unstable CLI options (names beginning `--X`) are now listed by `web3signer -X` (or `--Xhelp`), grouped by the command they belong to. `web3signer eth2 -X` lists only the `eth2` unstable options. [#1228][PR_1228]
- Azure Key Vault connections are now cached per credential/vault set instead of rebuilt on every key load, reducing bulk-load time. [#1222][PR_1222]
- Vert.x is upgraded to 5.2.0 and web3j to 6.0.0, together with the other dependencies and build plugins. [#1231][PR_1231]

### Bugs Fixed
- Azure Key Vault SECP256K1 signing now uses one official Azure SDK `CryptographyClient` per key instead of REST workaround. [#1222][PR_1222]
- Netty is upgraded to `4.2.17.Final` to prevent io_uring read stalls on reused connections.[#1222][PR_1222]
- `eth_signTransaction` for EIP-4844 transactions now returns the canonical signed transaction (`0x03 || rlp([...])`) instead of wrapping it in a blob network wrapper with empty sidecar lists. [#1231][PR_1231]
- HTTP API errors (Key Manager, signing, reload, unknown paths) now return `application/json` with a `{"code": <status>, "message": "..."}` body instead of plain text or HTML. A host allow-list rejection on the eth1 JSON-RPC endpoint (`POST /`) returns that body too, instead of an id-less `-32603` JSON-RPC envelope. eth1 JSON-RPC responses now send `Content-Type: application/json` instead of the misspelled `Content` header. [#1235][PR_1235]

### Breaking Changes
- The `http_vertx_worker_pool_rejected_total` metric is no longer exported, because Vert.x 5 does not report rejected worker tasks separately (they only occur once a pool has shut down). The bundled Grafana dashboard now charts worker queue delay instead. [#1231][PR_1231]
- `POST /reload` responses now use the shared `{"code": <status>, "message": "..."}` body: `202` returns `"code": 202` instead of `"status": "accepted"`, and `409` returns `"code": 409` instead of `"status": "error"`. `GET /reload` is unchanged. [#1235][PR_1235]

### CI/Build Enhancements
- Postgres-backed tests share one embedded Postgres cluster per test JVM and get a fresh database per test instead of a cluster per test, removing the embedded-Postgres startup and macOS shared-memory flakes. [#1242][PR_1242]
- Azure Key Vault acceptance tests now run against local Azure emulator instance instead of live Azure.
- OWASP dependency-check runs nightly again, scanning only the dependencies shipped in the distribution (`runtimeClasspath`) and publishing findings to GitHub code scanning. The NVD database is cached between runs and rebuilt weekly; the run fails if the NVD data is more than 48 hours old. Stale suppressions were removed and current false positives documented. [#1237][PR_1237]

### Security
- Docker base images re-pinned to their current multi-platform index digests. [#1230][PR_1230]
- Default image ships `ubuntu:26.04` + Eclipse Temurin JRE 25.0.4; distroless image ships Java 25.0.4 on `gcr.io/distroless/java25-debian13:nonroot`. [#1230][PR_1230]

[PR_1222]: https://github.com/Consensys-Incorporated/web3signer/pull/1222
[PR_1228]: https://github.com/Consensys-Incorporated/web3signer/pull/1228
[PR_1230]: https://github.com/Consensys-Incorporated/web3signer/pull/1230
[PR_1231]: https://github.com/Consensys-Incorporated/web3signer/pull/1231
[PR_1192]: https://github.com/Consensys-Incorporated/web3signer/pull/1192
[PR_1235]: https://github.com/Consensys-Incorporated/web3signer/pull/1235
[PR_1237]: https://github.com/Consensys-Incorporated/web3signer/pull/1237
[PR_1242]: https://github.com/Consensys-Incorporated/web3signer/pull/1242
[PR_1240]: https://github.com/Consensys-Incorporated/web3signer/pull/1240
[PR_1257]: https://github.com/Consensys-Incorporated/web3signer/pull/1257

---
## 26.7.0
### Features Added
- Support for Hashicorp Vault Kubernetes authentication [PR 1195](https://github.com/Consensys-Incorporated/web3signer/pull/1195)
- The eth1 signing endpoint (`POST /api/v1/eth1/sign/{identifier}`) accepts an optional `applyHash` field. It defaults to `true`, preserving the existing behaviour of applying Keccak-256 to `data` before signing. Setting it to `false` signs the caller-supplied `data` as a pre-computed digest, which must be exactly 32 bytes. Supported by file-based, AWS KMS and Azure Key Vault signers. [PR 1213](https://github.com/Consensys-Incorporated/web3signer/pull/1213)

### Bugs Fixed
- Fix Key Manager API (`POST /eth/v1/keystores`) accepting a keystore whose JSON `pubkey` field does not match the decrypted private key. A mismatched import now returns `status: "error"` for that entry rather than poisoning the slashing-protection database under the claimed (unverified) pubkey.

### Breaking Changes
- The Key Manager API (`DELETE /eth/v1/keystores`) now only attempts to delete keystore files that follow the `<pubkey>.yaml` naming convention. Files using a different naming scheme are left untouched. This replaces the previous approach of scanning all metadata files and matching on the EIP-2335 `pubkey` field, which is optional and not guaranteed to be correct.
- Java 25 is now required to build Web3Signer (previously Java 21).
- The `.tar.gz` and `.zip` binary distributions now require Java 25 on the host machine to run Web3Signer (previously Java 21).
- Docker images are unchanged — they have shipped Java 25 since 25.12.0.
- Contributors no longer need to install JDK 25 manually. The build now uses a Gradle toolchain (`JavaLanguageVersion.of(25)`) with the foojay resolver, so Gradle will auto-detect a locally installed JDK 25 and download Temurin 25 if none is found. The Gradle daemon itself can run on any JDK supported by Gradle 9 (17+).

### Security
- Update base docker image to latest LTS Ubuntu 26.04.

---
## 26.4.2
### Features Added
- Published Docker images are now signed (keyless cosign via Sigstore) and ship with SLSA provenance + SPDX SBOM attestations. See `docker/README.md` for verification commands.

### Infrastructure / CI
- Multi-arch Docker images (`linux/amd64`, `linux/arm64`) are now built on native GitHub-hosted runners instead of QEMU emulation, with a smoke test running per arch before push.

### Bugs Fixed
- Fix distroless image failing to load BLS native library under `docker run --read-only`. Issue [#1175][issue_1175], PR [#1176][PR_1176].

[issue_1175]: https://github.com/Consensys-Incorporated/web3signer/issues/1175
[PR_1176]: https://github.com/Consensys-Incorporated/web3signer/pull/1176

---
## 26.4.1
### Features Added
- Publish an additional hardened Docker image under the `-distroless` tag suffix (e.g. `consensys/web3signer:26.4.1-distroless`). Built from `gcr.io/distroless/java25-debian13:nonroot` — no shell, runs as non-root by default, and is compatible with `docker run --read-only`. See issue [#1151][issue_1151].
- Docker image labels migrated from the deprecated `org.label-schema.*` schema to the OCI Image Spec `org.opencontainers.image.*` annotations, which are consumed by modern registries and image-scanning tooling.

### Bugs Fixed
- Fix memory leak in the reload endpoint: removed validators were not being offloaded from the slashing-protection in-memory cache, and every reload unnecessarily re-registered all validators, causing old-gen heap pressure. Reload now processes only the delta of added/removed keys. PR [#1167][PR_1167].
- Fix jdbi parsed-SQL cache growth in `ValidatorsDao` by replacing inlined values and bindList expansions with parameterized array bindings. PR [#1170][PR_1170].

[issue_1151]: https://github.com/Consensys-Incorporated/web3signer/issues/1151
[PR_1167]: https://github.com/Consensys-Incorporated/web3signer/pull/1167
[PR_1170]: https://github.com/Consensys-Incorporated/web3signer/pull/1170

---
## 26.4.0
### Features Added
- Update Teku to 26.4.0 [Fulu upgrade for Gnosis chain]

---
## 26.3.0
### Security
- Updated base Docker image packages to address CVE-2025-68973 (HIGH severity) in gpgv package
- Docker images now apply all available security updates during build
- Updated jackson dependencies to 2.21.1

### Features Added
- Update Netty to 4.2.9.Final
- New `--logging-format` CLI option to select structured logging formats (PLAIN, ECS, GCP, LOGSTASH, GELF) without requiring custom Log4j2 configuration files. Issue [#1144][issue_1144] via PR [#1146][PR_1146].

### Bugs Fixed
- Fix unregistered validator IllegalStateException due to race condition in keymanager API

[issue_1144]: https://github.com/Consensys-Incorporated/web3signer/issues/1144
[PR_1146]: https://github.com/Consensys-Incorporated/web3signer/pull/1146

---
## 25.12.0
### Breaking Changes
#### Java25 Docker image
- Java 25, which is the latest Long-Term Support release, is used in the docker image instead of previous LTS version, Java 21.

#### `/reload` Endpoint Response Format Changed
- Now returns HTTP `202 Accepted` (previously `200 OK`) with JSON response body
- Returns `409 Conflict` with error message if reload already in progress
- **Migration:** Update automation to expect `202` status code instead of `200`

#### Removed Swagger UI CLI Option
- `--swagger-ui-enabled` option removed
- Access OpenAPI specs at https://consensys-incorporated.github.io/web3signer/

### Features Added
- **Enhanced `/reload` endpoint with status monitoring:**
  - New `GET /reload` endpoint to check reload operation status
  - Reports detailed status: `idle`, `running`, `completed`, `completed_with_errors`, `failed`
  - Exposes error counts when individual signer configurations fail to load
  - Distinguishes between complete success and partial success (some signers failed)
  - Provides timestamps and error messages for last reload operation
- Virtual thread-based signer loading for improved performance
- New file system signer loading configuration:
  - `--signer-load-timeout` (default: 60 sec) - Timeout per file during parallel processing
  - `--signer-load-batch-size` (default: 500) - Files processed per batch in parallel mode
  - `--signer-load-sequential-threshold` (default: 100) - Minimum files to trigger parallel processing
  - `--signer-load-parallel` (default: true) - Enable/disable parallel processing
- New `/reload` endpoint configuration:
  - `--reload-timeout` (default: 30 min) - Maximum time for entire reload operation
- Improved reload concurrency control prevents multiple simultaneous reloads
- Include Log4J JSON Template Layout library that provides predefined event templates such as ECS and GCP layouts in log4j configuration file. PR [#1140][PR_1140].

### Bugs Fixed
- Fix memory leak during reload API endpoint. Issue [#1073][issue_1073] via PR [#1135][PR_1135].
- Fix race condition in reload flag management when executor initialization fails synchronously

[issue_1073]: https://github.com/Consensys-Incorporated/web3signer/issues/1073
[PR_1135]: https://github.com/Consensys-Incorporated/web3signer/pull/1135
[PR_1140]: https://github.com/Consensys-Incorporated/web3signer/pull/1140

---
## 25.11.0
### Features Added
- Updated Teku libraries to add scheduling for Fulu upgrade for mainnet
  - Mainnet configuration for Fulu fork at epoch 411392 (2025-12-03 21:49:11 UTC)
- Added CLI option `-Xkey-manager-skip-keystore-storage` to eth2 subcommand preventing web3signer from writing keystores 
imported via the keymanager API to disk. This is *not* safe for operators who do not backup keystore.
- Added support for signing EIP-4844 transactions with blob data and versioned hashes (thanks [@mskrzypkows](https://github.com/mskrzypkows)!)
- Updated web3j library to 5.0.1
- Reduced Web3Signer docker image size. Removed unnecessary tools `iputils-ping, net-tools` from the image.

---
## 25.9.1
### Breaking changes
- `eea*` RPC methods and other privacy supporting classes have been removed since Privacy feature has been removed from [Besu 25.6.0](https://github.com/hyperledger/besu/releases/tag/25.6.0)

### Features Added
- Updated Teku libraries to add scheduling for Fulu testnets:
  - Holesky fork at epoch 165120 (2025-10-01 08:48:00 UTC)
  - Sepolia fork at epoch 272640 (2025-10-14 07:36:00 UTC)
  - Hoodi fork at epoch 50688 (2025-10-28 18:53:12 UTC)

---
## 25.9.0
### Upcoming Breaking Changes
- `eea*` RPC methods and other privacy supporting classes will be removed since Privacy feature has been removed from Besu 25.6.0 https://github.com/hyperledger/besu/releases/tag/25.6.0

### Features Added
- Updated Teku libraries dependencies for latest network configurations
- `health_status` RPC method under the eth1 subcommand
- Support for Optimism op-signer
- Acceptance tests for FULU fork.

### Bug Fixes
- Fixed EIP-712 `eth_signTypedData` JSON-RPC method. [#1012][issue_1012]

[issue_1012]: https://github.com/Consensys-Incorporated/web3signer/issues/1012

---
## 25.6.0
### Breaking Changes
- Deprecated CLI option `--azure-secrets-tags` has been removed. Use `--azure-tags` instead.
- USB Armory II/Interlock support has been removed.
- YubiHSM 2 support has been removed.

### Features Added
- Updated Teku libraries to handle BPO configurations

### Known Issues
Few customers have reported increasing memory usage with `/reload` API endpoint. We are investigating this issue and
will provide a fix in the next release. As a workaround, please restart Web3Signer instance when adding or removing keys.

---
## 25.4.1
### Upcoming Breaking Changes
> [!IMPORTANT]
> USB Armory II HSM and YubiHSM 2 keystore support will be removed in a future release.

### Features Added
- Scheduled Electra for Mainnet at epoch 364032 (May 7, 2025, 10:05:11am UTC)
- Scheduled Electra for Gnosis at epoch 1337856 (April 30, 2025, 14:03:40 UTC)

---
## 25.3.0

### Upcoming Breaking Changes
> [!IMPORTANT]
> USB Armory II HSM and YubiHSM 2 keystore support will be removed in a future release.

### Features Added
- Support for Hoodi testnet.
- Support for Gnosis Chiado testnet Electra configuration

### Bugs Fixes

---
## 25.2.0

> [!IMPORTANT]
> This is a required update for nodes running on Holesky or Sepolia network as it contains the configuration for the
Electra hard fork.

### Breaking Changes
- The behavior of reload API endpoint has been modified due to issue [#1018][issue_1018] implemented by PR [#1054][pr_1054].
The reload API call will remove stale keys therefore they will not be return in public_keys endpoint neither will be
able to perform any signing requests.
- The AWS secrets manager and KMS tag filter cli options has been modified. Following cli options has been removed:
```
--aws-kms-tag-names-filter
--aws-kms-tag-values-filter
--aws-secrets-tag-names-filter
--aws-secrets-tag-values-filter
```
The above are replaced by:
```
--aws-kms-tag <TagName>=<TagValue>
--aws-secrets-tag <TagName>=<TagValue>
```
- `--Xworker-pool-size` deprecated cli option has been removed. Use `--vertx-worker-pool-size` instead.

### Features Added
- Remove stale keys during reload API call. [#1018][issue_1018] [#1054][pr_1054]
- Use single cli option to specify AWS KMS tag name/value pairs. [#1055][pr_1055]
- Use single cli option to specify AWS Secrets tag name/value pairs. [#1055][pr_1055]
- Teku libraries updated to 25.2.0 with changes related to Electra hard fork on Holesky and Sepolia networks.

### Bugs Fixed:
- AWS KMS tag filter behavior has been fixed. [#1055][pr_1055]
- Upgrade Netty library to 4.1.118.Final to fix CVE-2025-24970.

[issue_1018]: https://github.com/Consensys-Incorporated/web3signer/issues/1018
[pr_1054]: https://github.com/Consensys-Incorporated/web3signer/pull/1054
[pr_1055]: https://github.com/Consensys-Incorporated/web3signer/pull/1055

---
## 24.12.0

### Breaking Changes
- Java 21 is required to build Web3Signer.
- Filecoin mode has been removed.

### Features Added
- Java 21 for build and runtime. [#995](https://github.com/Consensys-Incorporated/web3signer/pull/995)
- Electra fork support. [#1020](https://github.com/Consensys-Incorporated/web3signer/pull/1020) and [#1023](https://github.com/Consensys-Incorporated/web3signer/pull/1023)
- Teku and Besu libraries updated to 24.10.3 and 24.10.0 respectively.
- Commit Boost API - Get Public Keys [#1031][cb_pr1], Generate Proxy Keys [#1043][cb_pr2] and Request Signature [#1045][cb_pr3].

[cb_pr1]: https://github.com/Consensys-Incorporated/web3signer/pull/1031
[cb_pr2]: https://github.com/Consensys-Incorporated/web3signer/pull/1043
[cb_pr3]: https://github.com/Consensys-Incorporated/web3signer/pull/1045

### Bugs fixed
- Override protobuf-java to 3.25.5 which is a transitive dependency from google-cloud-secretmanager. It fixes CVE-2024-7254.

---
## 24.6.0

### Upcoming Breaking Changes
- This is the last Web3Signer release to use Java 17. Web3Signer will start mandating Java 21 for build and runtime after
this release. The Web3Signer docker image will also use Java 21, however, binary distributions (.tar.gz/.zip) will
require Java 21 to be available on the host machine.
- This is the last Web3Signer release to use the "filecoin" mode. The "filecoin" mode will be removed in a future release.

### Breaking Changes
- Use Java 21 for build and runtime. Remove Java 17 variant of docker image. zip/tar.gz distributions will require Java 21 or above to run Web3Signer.
- The Web3Signer metrics are now compatible with OpenMetrics data model. This results in the names of counter metrics to append `_total` suffix. Users may need to update their dashboards to reflect these changes.

### Features Added
- Added endpoint `/api/v1/eth2/ext/sign/:identifier` which is enabled using cli option `--Xsigning-ext-enabled=true`.
This endpoint allows signing of additional data not covered by the remoting API specs. [#982](https://github.com/Consensys-Incorporated/web3signer/pull/982)

### Bugs fixed
- Update transitive dependency threetenbp and google cloud secretmanager library to fix CVE-2024-23082, CVE-2024-23081
- Update bouncycastle libraries to fix CVE-2024-29857, CVE-2024-30171, CVE-2024-30172
- Update Teku libraries to 24.3.1
- Update Vert.x to 4.5.7 (which include fixes for CVE-2024-1023)
- Fix Host Allow List handler to handle empty host header
- Update Postgresql JDBC driver to fix CVE-2024-1597
- Fix cached gvr to be thread-safe during first boot. [#978](https://github.com/Consensys-Incorporated/web3signer/issues/978)

---
## 24.2.0

This is a required update for Mainnet users containing the configuration for the Deneb upgrade on March 13th. This update is required for Gnosis Deneb network upgrade on March 11th. For all other networks, this update is optional.

### Upcoming Breaking Changes
- `--Xworker-pool-size` cli option will be removed in a future release. This option has been replaced with `--vertx-worker-pool-size`

### Features Added
- Add Deneb configuration for Mainnet [#971](https://github.com/Consensys-Incorporated/web3signer/pull/971)
- Improve Key Manager API import operation to use parallel processing instead of serial processing

### Bugs fixed
- Ensure that Web3Signer stops the http server when a sigterm is received

## 24.1.1

This is an optional release for mainnet Ethereum and it includes the updated network configuration for the Sepolia, Holesky and Chiado Deneb forks.

## 24.1.0

This is an optional release for mainnet Ethereum and it includes the updated network configuration for the Goerli Deneb fork.

### Upcoming Breaking Changes
- `--Xworker-pool-size` cli option will be removed in a future release. This option has been replaced with `--vertx-worker-pool-size`.

### Bugs fixed
- Update reactor-netty-http to fix CVE-2023-34062

### Features Added
- Add Deneb configuration for Goerli [#960](https://github.com/Consensys-Incorporated/web3signer/pull/960)

## 23.11.0
### Upcoming Breaking Changes
- `--Xworker-pool-size` cli option will be removed in a future release. This option has been replaced with `--vertx-worker-pool-size`.

### Bugs fixed
- Update netty to fix CVE-2023-44487

### Features Added
- Google Cloud Secret Manager bulk loading support for BLS keys in eth2 mode via PR [#928](https://github.com/Consensys-Incorporated/web3signer/pull/928) contributed by [Sergey Kisel](https://github.com/skisel-bt).
- Removed hidden option `--Xtrusted-setup` as Web3Signer does not need KZG trusted setup file anymore.
- Make Vert.x worker pool size configurable using cli option `--vertx-worker-pool-size` (replaces the now deprecated: `--Xworker-pool-size`). [#920](https://github.com/Consensys-Incorporated/web3signer/pull/920)

## 23.9.1

### Breaking Changes
- Remove --validator-ids option from watermark-repair subcommand [#909](https://github.com/Consensys-Incorporated/web3signer/pull/909)

### Features Added
- Aws bulk loading for secp256k1 keys in eth1 mode [#889](https://github.com/Consensys-Incorporated/web3signer/pull/889)
- Add High Watermark functionality [#696](https://github.com/Consensys-Incorporated/web3signer/issues/696)
  - Update `watermark-repair` subcommand with new options `--set-high-watermark`, `--remove-high-watermark` [#912](https://github.com/Consensys-Incorporated/web3signer/pull/912)
  - Add GET `/highWatermark` to eth2 endpoints [#908](https://github.com/Consensys-Incorporated/web3signer/pull/908)
- Add network configuration for revised Holesky testnet

## 23.9.0

### Features Added
- Signing support for BlobSidecar and BlindedBlobSidecar in Deneb fork.
- Add `--azure-response-timeout` to allow request response timeout to be configurable, the field `timeout` is also accepted in the Azure metadata file. [#888](https://github.com/Consensys-Incorporated/web3signer/pull/888)
- Bulk load Ethereum v3 wallet files in eth1 mode.
- Eth2 Signing request body now supports both `signingRoot` and the `signing_root` property
- Add network configuration for Holesky testnet
- Add `eth_signTypedData` RPC method under the eth1 subcommand. [#893](https://github.com/Consensys-Incorporated/web3signer/pull/893)

### Bugs fixed
- Upcheck was using application/json accept headers instead text/plain accept headers

## 23.8.1

### Bugs fixed
- Update grpc library to version 1.57.2 to fix CVE-2023-33953

## 23.8.0

### Breaking Changes
- Use Java 17 for build and runtime. Remove Java 11 variant of docker image. zip/tar.gz distributions will require Java 17 or above to run Web3Signer.
- Eth2 Azure command line option --azure-secrets-tags is now deprecated and is replaced with --azure-tags. The --azure-secrets-tags option will be removed in a future release.

-
### Features Added
- Add support for SECP256K1 remote signing using AWS Key Management Service. [#501](https://github.com/Consensys-Incorporated/web3signer/issues/501)
- Azure bulk mode support for loading multiline (`\n` delimited, up to 200) keys per secret.
- Hashicorp connection properties can now override http protocol to HTTP/1.1 from the default of HTTP/2. [#817](https://github.com/Consensys-Incorporated/web3signer/pull/817)
- Add --key-config-path as preferred alias to --key-store-path [#826](https://github.com/Consensys-Incorporated/web3signer/pull/826)
- Add eth_signTransaction RPC method under the eth1 subcommand [#822](https://github.com/Consensys-Incorporated/web3signer/pull/822)
- Add eth_sendTransaction RPC method under the eth1 subcommand [#835](https://github.com/Consensys-Incorporated/web3signer/pull/835)
- Add EIP-1559 support for eth1 public transactions for eth_sendTransaction and eth_signTransaction [#836](https://github.com/Consensys-Incorporated/web3signer/pull/836)
- Add Azure bulk loading for secp256k1 keys in eth1 mode [#850](https://github.com/Consensys-Incorporated/web3signer/pull/850)
- Added Gnosis configuration for the 🦉 CAPELLA 🦉 network fork due at epoch 648704, UTC Tue 01/08/2023, 11:34:20 [#865](https://github.com/Consensys-Incorporated/web3signer/pull/865)
- Java 17 for build and runtime. [#870](https://github.com/Consensys-Incorporated/web3signer/pull/870)
- Update internal teku library to 23.8.0 [#876](https://github.com/Consensys-Incorporated/web3signer/pull/876)
- Add support for [Lukso network](https://lukso.network/) `--network=lukso`
- Deprecate `signingRoot` while currently supporting both `signingRoot` and `signing_root` in Eth2 signing request body.

### Bugs fixed
- Support long name aliases in environment variables and YAML configuration [#825](https://github.com/Consensys-Incorporated/web3signer/pull/825)

---
## 23.6.0

As part of our ongoing commitment to deliver the best remote signing solutions, we are announcing a change in our product offerings.

We have decided to deprecate our [EthSigner](https://github.com/Consensys/EthSigner) product to focus our efforts on enhancing Web3Signer, our newly comprehensive remote signing solution. This is rooted in our strategy to streamline our offerings and focus on a single, robust product that will provide functionality for both transaction and Ethereum validator signing. We hope this makes it applicable to all your use-cases like public Ethereum signing, staking infrastructure offerings, and in private network contexts.

Rest assured, we are not dropping existing EthSigner functionality. We are updating Web3Signer to incorporate the functionalities of EthSigner alongside everything else in Web3Signer. We will ensure a smooth transition by maintaining EthSigner with necessary patches for an additional six months. We hope this provides ample time for any necessary migration to Web3Signer.

**We have begun adding EthSigner functionality to Web3Signer. This is a work in progress and not complete.**

### Features Added
- Optional Azure bulk loading tags support using cli option `--azure-secrets-tags`.
- Support Prometheus Push Gateway Metrics [#796](https://github.com/Consensys-Incorporated/web3signer/pull/796)
- Cache Genesis Validators Root (GVR) in-memory on first database lookup. This would eliminate further database lookups
for GVR during sign operations and improve their performance. [#600](https://github.com/Consensys-Incorporated/web3signer/issues/600)
- Add RPC proxy support to execution client under the eth1 subcommand [#775](https://github.com/Consensys-Incorporated/web3signer/pull/775)
- Add eth_accounts RPC method under the eth1 subcommand [#784](https://github.com/Consensys-Incorporated/web3signer/pull/784)

### Bugs Fixed
- Upgrade jackson and vertx to upgrade snakeyaml to 2.0 to fix CVE-2022-1471
- Fixed handling of very large number (30,000+) of signing metadata files with Hashicorp connection by introducing
experimental flag to disable parallel processing `--Xmetadata-files-parallel-processing-enabled`.
[#794](https://github.com/Consensys-Incorporated/web3signer/pull/794)
- Fixed startup error with web3signer where openAPI spec cannot be loaded [#772](https://github.com/Consensys-Incorporated/web3signer/issues/772)
- Removed unmaintained and out-of-date helm chart [#802](https://github.com/Consensys-Incorporated/web3signer/pull/802)

---
## 23.3.1
### Features Added
- Add support for Capella milestone in Mainnet
- Enhanced Healthcheck endpoint reporting status of loading of signers keys [#738](https://github.com/Consensys-Incorporated/web3signer/pull/738)
- Optional AWS endpoint overriding for bulk loading `--aws-endpoint-override`. Useful for local testing against localstack. [#730](https://github.com/Consensys-Incorporated/web3signer/issues/730)

### Bugs Fixed
- Update of Azure libraries (transitive via signers library) and manual override to fix CVE-2023-1370
- Fix issue with some third party libraries not including logs in the web3signer logs due missing slf4j2 library

---
## 23.3.0
### Breaking Changes
- Slashing protection database schema has been updated to support indexes with bigint type and after the upgrade will no longer work with older versions of Web3Signer.

### Features Added
- Add support for Capella milestone in Goerli
- Introduced cli option `--key-store-config-file-max-size` to change the default value of configuration file size. [#719](https://github.com/Consensys-Incorporated/web3signer/issues/719)

### Bugs fixed
- Fix issue with slashing protection database failing once reaching max integer index value [#705](https://github.com/Consensys-Incorporated/web3signer/issues/705)
- Fix issue with Web3Signer startup when configuration file size is greater than 3 MB [#719](https://github.com/Consensys-Incorporated/web3signer/issues/719)

---
## 23.2.1
### Features Added
- Add support for Capella milestone in Sepolia
- Add Block signing support for Capella

### Bugs fixed
- Upgrade to Vertx 4.3.8 to address CVE-2023-24815
- Updated docker image with latest libssl3

---
## 23.2.0
### Features Added
- AWS Secrets Manager bulkload mode can now load multiple keys from same secret where keys are separated by line terminating
character (such as `\n`). [#706](https://github.com/Consensys-Incorporated/web3signer/issues/706)

---
## 23.1.0
### Features Added
- Multiple Signing Key configurations can be specified in single YAML file using triple-dash `---` separator.
[#689](https://github.com/Consensys-Incorporated/web3signer/issues/689)
- Reloading of signing key configuration file (via `/reload` endpoint) will process new or modified configuration files. [#689](https://github.com/Consensys-Incorporated/web3signer/issues/689)
- Updated Teku libraries version to 22.12.0

### Bugs Fixed
- Upgrade various dependencies including netty libraries to address CVE-2022-41881 and CVE-2022-41915

---
## 22.11.0
### Breaking Changes
- Slashing protection imports will now only fail for an individual validator instead for all validators allowing partial
import if there is valid and invalid data.

### Features Added
- Introduced cli option to specify Hikari configuration for pruning database connection [#661](https://github.com/Consensys-Incorporated/web3signer/issues/661)
- Better database pruning default values: Pruning enabled by default with
`slashing-protection-pruning-epochs-to-keep = 250`, `slashing-protection-pruning-at-boot-enabled = false` and
`slashing-protection-pruning-interval = 12`.
- Improved performance for slashing protection import
- Introduced experimental cli option `--Xslashing-protection-db-connection-pool-enabled` to disable internal database
connection pool (Hikari) to allow using external database connection pool such as pgBouncer.
`--slashing-protection-db-pool-configuration-file` and `--slashing-protection-pruning-db-pool-configuration-file` can be
reused to specify PG Datasource properties. [#662](https://github.com/Consensys-Incorporated/web3signer/issues/662)
- Added new subcommand watermark-repair to update low watermarks

---

## 22.10.0

### Features Added
- Log eth2 network configuration on startup [#640](https://github.com/Consensys-Incorporated/web3signer/issues/640)
- Updated internal Teku libraries to 22.10.1
- Updated HikariCP to 5.0.1

### Bugs Fixed
- Upgrade jackson libraries to fix CVE-2022-25857, CVE-2022-38751, CVE-2022-38752 and CVE-2022-42003
- Upgrade protobufs to fix CVE-2022-3171

## 22.8.1
### Features Added
- Updated internal Teku libraries to 22.8.1. This update includes Bellatrix network upgrade and merge transition configuration for Mainnet.

## 22.8.0
### Features Added
- Added health check endpoint [#538](https://github.com/Consensys-Incorporated/web3signer/issues/538).
- Introduced `--slashing-protection-db-health-check-timeout-milliseconds` to specify the timeout of the slashing db health check procedure.
- Introduced `--slashing-protection-db-health-check-interval-milliseconds` to specify the interval between slashing db health check procedures.
- Updated Teku libraries version (support for Prater/Görli merge).

### Bugs Fixed
- Updated to PostgreSQL JDBC driver to 42.4.1. Resolves a potential vulnerability CVE-2022-31197.

## 22.7.0
### Features Added
- Support register validator API endpoint [#577](https://github.com/Consensys-Incorporated/web3signer/issues/577)
- Version information available in metrics through `process_release` [#480](https://github.com/Consensys-Incorporated/web3signer/issues/480)
---

## 22.6.0
### Features Added
- Support for Sepolia network (updated Teku support libraries).
- Added new metric `eth2_slashingprotection_database_duration` to track time spent performing database queries during either block or attestation signing operations
- Private keys bulk loading from AWS Secrets Manager via cli options in eth2 mode [#499](https://github.com/Consensys-Incorporated/web3signer/issues/499)

### Bugs Fixed
- Fix issue where signing_signers_loaded_count metric didn't update after refresh endpoint was used to update loaded keys
---

## 22.5.1
### Breaking Changes
- Removed network definition for kintsugi testnet

### Features Added
- Eth2 keystore bulk loading allowing a directory of keystores to be loaded without config files
- Added support for ropsten testnet

### Bugs Fixed
- Fixes issue when using key manager delete API failed when there was no slashing protection data [#537](https://github.com/Consensys-Incorporated/web3signer/issues/537)

---
## 22.5.0
### Breaking Changes
- ETH2 Mode - block signing request (BLOCK_V2), starting from BELLATRIX fork, use block_header instead of block. [#547](https://github.com/Consensys-Incorporated/web3signer/pull/547)

### Features Added
- Added support for optimized block signing requests starting from Bellatrix fork. [#437](https://github.com/Consensys-Incorporated/web3signer/issues/437)
- Early access: Support for Gnosis network in Eth2 mode. `--network gnosis`

### Bugs Fixed
- Keys loaded using the AWS secrets manager with environment config didn't work when using web identity tokens due to missing sts library.

---
## 22.4.1

### Features Added
- Update various library dependencies

---
## 22.4.0

### Breaking Changes
- Because the web3signer docker image uses the latest LTS tag (ubuntu:latest), the container host may require an update to the latest container runtime. See [Ubuntu bug](https://bugs.launchpad.net/ubuntu/+source/libseccomp/+bug/1916485) for more details.

### Features Added
- Migrate from the deprecated `vertx-web-api-contract` module to `vertx-web-openapi` [#506](https://github.com/Consensys-Incorporated/web3signer/pull/506)
- Migrate jackson `ObjectMapper` instances to `JsonMapper` and `YamlMapper` builders to resolve deprecation warnings [#507](https://github.com/Consensys-Incorporated/web3signer/pull/507)
- Add `iputils-ping` and `net-tools` to docker image to support waiting for dependent services in tools such as docker-compose and Kubernetes [#525](https://github.com/Consensys-Incorporated/web3signer/pull/535)
- Updated Teku libraries to provide support for `kiln` network
- Support for BLS private keys in AWS Secrets Manager
- Early access support for [eth2 Key Manager API](https://ethereum.github.io/keymanager-APIs/)

### Bugs Fixed
- Upgrade Vertx to 4.x, signers to 2.0.0 and various other dependencies to latest versions [#503](https://github.com/Consensys-Incorporated/web3signer/pull/503)
- DB scripts executed in numeric order (instead of alphanumeric) when using docker instead of flyway to execute [#526](https://github.com/Consensys-Incorporated/web3signer/pull/526)

---
## 21.10.6
### Bugs Fixed
- Updated to PostgreSQL JDBC driver to 42.3.3. Resolves a potential vulnerability CVE-2022-21724.

---
## 21.10.5
### Bugs Fixed
- Updated to log4j 2.17.1. Resolves two potential vulnerabilities which are only exploitable when using custom log4j configurations that are either writable by untrusted users or log data from the `ThreadContext`.

---
## 21.10.4
### Bugs Fixed
- Updated log4j to 2.17.0 to mitigate potential DOS vulnerability when the logging configuration uses a non-default Pattern Layout with a Context Lookup.

---
## 21.10.3

### Bugs fixed
- Updated log4j to 2.16.0 to mitigate JNDI attack via thread context.
---

## 21.10.2

### Bugs fixed
- Fix multi-arch JDK17 variant docker image to bundle Java 17 instead of Java 11

## 21.10.1

### Features Added
- Docker images are now published with multi-arch support including Linux/amd64 and Linux/arm64
- The default docker image now uses JDK 17 instead of 11. The JDK 11 image is still available with the version suffix `-jdk11`

### Breaking Changes
- The docker image now uses `web3signer` as user/group instead of `root` which may result in compatibility/permissions issues with existing directory mounts.

### Bugs Fixed
- Updated log4j and explicitly disabled format message lookups.

---

## 21.10.0

### Features Added
- Upgrade to signers 1.0.19 allows empty password files to be read when creating a Signer [#432](https://github.com/Consensys-Incorporated/web3signer/pull/432)
- Upgrade Teku libraries version to 21.9.2 to provide support for Altair fork in mainnet [#435](https://github.com/Consensys-Incorporated/web3signer/pull/435)

### Breaking Changes
- Upgrade to signers 1.0.19 removes support for deprecated SECP256K1 curve in Azure remote signing [#432](https://github.com/Consensys-Incorporated/web3signer/pull/432)

## 21.8.1

### Features Added
- Added sign type BLOCK_V2 to support block signing for Phase0, Altair and future forks (Eth2 mode). BLOCK is not removed for
backward compatibility with PHASE0 blocks.
- Upgraded Teku libraries to 21.8.2 which added support for Altair upgrade on Prater testnet at epoch 36660.

### Bugs fixed
- Unable to sign blocks on testnet Pyrmont after Altair fork. (Thanks to [Sephiroth](https://github.com/3eph1r0th) for reporting it.)

## 21.8.0

### Features Added
- Upgraded Teku libraries to 21.8.1. Added support for Altair upgrade on the Pyrmont testnet at epoch 61650.

### Bug fixed
- Spelling mistake fixed in Eth2 OpenApi spec

## 21.7.0

### Breaking changes
- `--network` flag for `eth2` subcommand is now mandatory and defaults to `mainnet`. Use appropriate network when running web3signer for a testnet.
- Database migration scripts (V8__*.sql and V9__*.sql) are required to be executed for this release if slashing protection is used.

### Features Added
- Introduced `--slashing-protection-db-pool-configuration-file` to specify Hikari connection pool configuration file.
- Upgraded gradle and various plugin versions. Switched to new dependency license reporting plugin. Project can now be compiled against JDK 16.
- Introduced --network cli option for Eth2 mode. Defaults to mainnet. Should match the option used by Teku at runtime.
- Upgraded Teku libraries.
- Eth2 slashing protection now has an additional safeguard that prevents multiple signed blocks or attestations being inserted using database constraints.
- Use adoptopenjdk/openjdk11:x86_64-ubuntu-jre-11.0.11_9 as docker base image.

### Bugs fixed
- Fixed transaction deadlock at start up during same validators registration (>10000) from multiple web3signer instances.

## 21.3.0

### Features Added
- Metrics on Vertx event loop and worker thread pools

### Bugs Fixed
- Eth2 slashing protection pruning on startup now runs on a separate thread, so it doesn't block application startup and http requests
- Eth2 slashing protection pruning was deleting the incorrect amount of data in some cases

## 21.2.0

### Features Added
- Reload API endpoint to load new keys
- Slashing protection database pruning for Eth2
- Publish binaries to Cloudsmith
- Resolve Signers from Cloudsmith

### Bugs Fixed
- Fixed build failure when checked out as a shallow clone. Shallow clones are still not recommended as the version number cannot be determined correctly.
- Change reference tests git submodule to https so a github account isn't required to build web3signer

## 21.1.0

### Features Added
- Azure secrets managed identity mode
- Check that database matches expected version on startup
- Added basic Eth2 grafana dashboard (https://grafana.com/grafana/dashboards/13687)
- Updated openjdk docker base image

### Bugs Fixed
- Improve query performance of attestations and blocks by adding indexes
- Azure configuration files could only be parallel-processed in a batch of 10 due to a bug in Azure libraries
- Incorrect options in the openapi spec for Eth2 signing API
- Fix the external link for documentation on OpenAPI documentation

## 20.11.0

### Features Added

- Interlock/Armory II HSM keystore support
- Eth2 slashing protection data able to be exported and imported from json file (Interchange format V5)
- Eth2 signing API returned body matches incoming request content-type in either plain-text or json
- Only able to sign for a single Genesis validators root (which is defined by the first received request after creation)
- Do not sign below watermark (regardless of if matching existing entry)

### Bugs Fixed
- Eth2 slashing protection metrics category was not working on CLI
- Update Filecoin RPC to be compatible with Lotus remote wallet API
- Eth2 slashing protection returns a 412 http status code for a slashing violation
- Signing with empty slashing database would sometimes fail due multiple genesis validator root values inserting concurrently
- Resolved help text anomalies on command line

## 0.2.0

### Features Added
- Separate application into eth2, eth and Filecoin commands that can be run independently
- Eth2 slashing protection. Requires a PostgreSQL database to store eth2 signed blocks and attestations
- Ethereum secp256k1 signing of data
- Use yaml configuration of signing keys
- Support for Filecoin JSON RPCs
- Azure secret vault support for eth2 keys to load all secrets from a given vault
- Added a Prometheus metrics endpoint
- Use native BLS signing and verification
- Added helm charts

## 0.1.0

Initial release of Eth2Signer

### Features Added
- Signing of data using a BLS key. Supports BLS private keys in unencrypted files, BLS12-381 keystore files and keys in Hashicorp
- TLS support for rest API
- OpenAPI documentation
