# Personal development signing key

`development.p12` is the persistent PKCS#12 signing key for a local Kanji Hour APK. Its matching password is stored in `development-password.txt`, and signing commands pass the password file rather than its contents as a process argument. Neither file belongs in Git, release assets, or shared source archives. Only this README belongs in the repository.

- Alias: `kanji-hour`
- RSA 2048-bit key; validity 10,000 days from creation.
- A new local key gets a cryptographically random password; both new files have owner-only permissions.

When both files are absent, the first build creates a new signing identity. When both are present, the script preserves them. If only one exists, restore its matching file from a private backup: the script refuses to generate a replacement silently. A failed first key generation can also leave a password without a completed key; inspect that failure before choosing a separate clean development checkout.

Keep the key for the already installed APK and its password backed up privately, separately from this repository. Do not delete or regenerate them for routine updates. Anyone with both files can sign an update as this app.

The public SHA-256 signing-certificate fingerprint of the existing personal APK is:

```text
9e7aaa58b255e01820667c509475825e247859219fbdd7aa2932ffcc99d76fc3
```

Collaborators can build and test with their own newly generated local key. Such an APK has a different signing certificate and **cannot update the existing personal installation in place**, even with the same application ID. Updates that preserve its settings must be signed privately with the original matching key; repository access does not grant access to that key.
