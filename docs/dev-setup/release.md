# Release signing and publishing

`.github/workflows/release.yml` turns a pushed `v*` tag into a GitHub Release that carries the
signed APK and its SHA-256. It builds `:app:assembleRelease` (R8, no signing config in Gradle),
aligns the APK with `zipalign`, signs it with `apksigner` using the key from the repository's
secrets, verifies it, and attaches `Phopencode-v<version>.apk` and
`Phopencode-v<version>.apk.sha256` to the release of that tag.

The workflow runs only on a tag push, never on a pull request, so code from a fork never runs
with the secrets. Its default token permission is none; the build job reads, and only the
publishing job, which sees no signing secret, may write the release.

## 1. Create the signing key (once)

Do this on a machine you trust. Every file of this guide lives in `~/phopencode-signing`, a
private folder outside any git checkout, and every command names it with its full path. Never
create the keystore or its base64 form inside the repository: the repository is public, and a
stray file there could be committed. `keytool` comes with the JDK (`docs/dev-setup/jdk.md`).

```sh
mkdir -p ~/phopencode-signing && chmod 700 ~/phopencode-signing
keytool -genkeypair -v \
  -keystore ~/phopencode-signing/phopencode-release.jks -storetype PKCS12 \
  -alias phopencode \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=<your name or project name>"
```

- RSA 4096 with a validity of 10000 days (about 27 years). Android requires the certificate to
  stay valid for as long as the app gets updates, so keep the validity long.
- `keytool` asks for the keystore password. With PKCS12 the key password is the same as the
  keystore password; use that value for both `RELEASE_KEYSTORE_PASSWORD` and
  `RELEASE_KEY_PASSWORD`.
- Choose your own alias; `phopencode` above is only an example.
- Generate long random passwords with a password manager.

Note the certificate fingerprint; you will compare downloads against it:

```sh
keytool -list -v -keystore ~/phopencode-signing/phopencode-release.jks -alias phopencode | grep 'SHA256:'
```

## 2. Back it up offline

The keystore file and both passwords are the app's identity. Android installs an update only when
it is signed with the same key as the installed app.

- Back up the keystore file and the passwords offline, in at least two places (for example an
  encrypted USB drive and a password manager export kept apart from it).
- Never commit the keystore, its base64 form or any password to this repository or any other.
  The repository is public.
- If the keystore or its passwords are lost, no future APK can update an installed copy: every
  user would have to uninstall the app, losing its data, and install a new one.
- If the key leaks, anyone can sign an APK that installs as an update. Rotate it (see
  `apksigner rotate`) and tell users.

## 3. Add the four repository secrets

Encode the keystore as base64 on one line, next to the keystore and readable only by you:

```sh
(umask 077 && base64 -w 0 ~/phopencode-signing/phopencode-release.jks \
  > ~/phopencode-signing/phopencode-release.jks.b64)   # GNU coreutils
# macOS: (umask 077 && base64 -i ~/phopencode-signing/phopencode-release.jks \
#   -o ~/phopencode-signing/phopencode-release.jks.b64)
```

With the GitHub CLI. `--repo` names the repository, so the commands work from any folder and
need no checkout; replace `<owner>/<repo>` with this repository. Each command reads the value
from a file or a prompt, so it never lands in your shell history:

```sh
gh secret set RELEASE_KEYSTORE_BASE64 --repo <owner>/<repo> \
  < ~/phopencode-signing/phopencode-release.jks.b64
gh secret set RELEASE_KEYSTORE_PASSWORD --repo <owner>/<repo>   # paste at the prompt
gh secret set RELEASE_KEY_ALIAS --repo <owner>/<repo>           # for example: phopencode
gh secret set RELEASE_KEY_PASSWORD --repo <owner>/<repo>        # paste at the prompt
```

Or in the web UI: the repository's Settings, Secrets and variables, Actions, New repository
secret, once per name:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the contents of `~/phopencode-signing/phopencode-release.jks.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | the alias given to `keytool` |
| `RELEASE_KEY_PASSWORD` | the key password (the keystore password for PKCS12) |

Once the secret is set, delete the base64 file: it is the keystore in another form. Keep only
the `.jks` file and its offline backups.

```sh
shred -u ~/phopencode-signing/phopencode-release.jks.b64   # macOS: rm -P <same path>
```

When a secret is missing, the workflow fails with the name of that secret and prints no value.

## 4. Cut a release

1. Set `versionName` (and raise `versionCode`) in `app/build.gradle.kts` and update
   `CHANGELOG.md`; commit and push to `master`.
2. Tag that commit with `v` followed by exactly the `versionName`, and push the tag:

   ```sh
   git tag -a v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```

3. The Release workflow runs on the tag. It stops when the tag does not match the `versionName`
   of the built APK, so a mistyped tag publishes nothing. A tag with a suffix, such as
   `v0.2.0-rc.1`, is published as a pre-release.
4. When it finishes, the release page of the tag lists `Phopencode-v0.1.0.apk` and
   `Phopencode-v0.1.0.apk.sha256`. Running the workflow again for the same tag replaces the
   files.

The workflow never creates or moves tags. To redo a release after fixing the code, delete the
release and the tag on GitHub and locally, then tag again.

## 5. Verify a downloaded APK

Put both files in one directory and check the hash:

```sh
sha256sum -c Phopencode-v0.1.0.apk.sha256
```

Check the signature and the signer certificate; `apksigner` is in the Android SDK build tools
(`$ANDROID_HOME/build-tools/<version>/apksigner`):

```sh
apksigner verify --print-certs Phopencode-v0.1.0.apk
```

The output must show `Signer #1 certificate SHA-256 digest:` with the fingerprint you noted in
step 1 (the same hex, without colons), and no `CN=Android Debug`. The hash file alone only proves
the download is complete; the certificate proves who signed it.
