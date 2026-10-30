# Master development signing key

`master-development.p12` is an intentionally public key for rolling master and
versioned GitHub testing builds. It keeps their signing identity stable so later
builds can update earlier ones. It is not a production signing key. The updater
uses embedded channel metadata to keep master and versioned releases separate.

- Store type: PKCS12
- Alias: `aarchive-master-development`
- Store and key password: `android`

Keep this file unchanged to preserve update compatibility. Existing APKs signed
with a different key, including locally generated debug APKs, must be uninstalled
before installing a master build.
