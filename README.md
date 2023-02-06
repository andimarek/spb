# Simple and secure personal backup

Spb is a simple and secure personal backup tool.
It backups folder into S3 while encrypting the files on the client side.

## Overview

The fundamental idea is simple: spb scans a locale folder and uploads files into a S3 bucket if needed.
By leveraging [S3 versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html) it provides
a full history of all files every backed up.

A file is client side encrypted (with AES-GCM) before being backed up and only uploaded if the SHA256 value has been
changed
since the last backup.

Spb aims to be as simple as possible, while practically usable and secure.

It is written in Java and compiled to a binary via [GraalVM](https://www.graalvm.org/) and available as CLI for Mac,
Windows and Mac.

For the critical encryption logic it relies on as few dependencies as possible:

1. JDK built in encryption algorithm
2. [AWS encryption sdk](https://github.com/aws/aws-encryption-sdk-java) (which itself relies only on Bouncy Castle)
3. [Bouncy Castle](https://www.bouncycastle.org/)

Additionally, it relies on

1. [AWS SDK for Java 2.0](https://github.com/aws/aws-sdk-java-v2) for accessing S3
2. [Logback](https://logback.qos.ch/) and [SLF4J](https://www.slf4j.org/) to write log files
3. [picocli](https://picocli.info/) for providing a nice command line interface

## How to use it

Download a [spb release](https://github.com/andimarek/spb/releases) for Mac, Linux or Windows.

Additionally to the terminal output spb writes a log files with all the details atl `~/spb.log`.

Spb requires a config file `~/spb.config`.
It allows you to config the S3 bucket to write the backup, the secret key to use for the encryption and
the list of folders to back up.

Each folder to back up is considered "one backup".

Example config file:

```properties
bucket.name=my-backups-123ABC
secret.key=<256-bit-secret-key-in-base64>
backup.0.folder=/Users/andi/my-data
backup.0.name=data
backup.1.folder=/Users/andi/texts
backup.1.name=texts
```

`bucket.name` is the S3 bucket name.
`secret.key` is the private 256 bit secret key, Base64 encoded which is used to encrypt all data
send to S3. You can generate a new key with `spb generate-key`

`backup.N.name` and `backup.N.folder` configures the folders to back up. Each folder has a name
assigned, which identifies the backup and which is also used as S3 folder name.

Spb requires access to the S3 bucket named in the config with the following actions:

```
"s3:PutObject",
"s3:GetObject",
"s3:ListBucketVersions",
"s3:ListBucket",
"s3:DeleteObject",
"s3:GetObjectVersion"
````

The credentials for the S3 access can be loaded via:

- Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
- Web Identity Token credentials from system properties or environment variables
- Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI

spb offers the following commands:

```
backup   initiate backup of a folder
restore  restore previously backed up files
list     list all backed up files
verify   verify backups
generate-key  generate a new random key
```

Example to initiate a backup:

```shell
spb backup
```

The verify commands restores all the backed up and verifies their integrity by comparing
the downloaded SHA256 checksums with the expected checksums.

```shell
spb verify
```

To restore a specific file from the backup `documents`:

```shell
spb restore --backup-name=documents --target-folder=documents-restored --file=tax/tax-2020.pdf
```

To restore all files from the backup `documents`:

```shell
spb restore --backup-name=documents --target-folder=documents-restored 
```

To find a specific file in the backups:

```shell
spb list --file-pattern="folder/important.txt"
```

To find a specific file in the backups including history:

```shell
spb list --file-pattern=".*/important.*" --historical
```

This will show all versions of all matching files including the version id.
The version id can then be used to restore this specific version:

```shell
spb restore --backup-name=documents --file-name="folder/important.txt" --target-folder="out" --version-id="sesN1qhjd6h13bsG.IIUfXeAFYE5AX7h"
```

The full list of options are available via `spb --help` and `spb <command> --help`.

## Details

Every file is saved as two different objects in S3: one metadata object and one content object.
These files are always written together, everytime a file is backed up.

The full keys in the S3 bucket are `/backup-name/<file-path-hash>/metadata` nad `/backup-name/<file-path-hash>/content`
The `backup-name` is the name of backup configured in the spb config file.

The `<file-path-hash>` is a `AESCMAC` hash generated with the spb secret key and the relative file path
of the backed up file.

The content object contains the content of the backed up file, encrypted
as [AWS Encryption SDK](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/concepts.html)
message. The message format is
documented [here](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/message-format.html).

The algorithm used is `AES_256_GCM_HKDF_SHA512_COMMIT_KEY` which basically means it is encrypted with AES-GMC.
The AES key used is the spb secret key.

The metadata file contains encrypted metadata about the backed up files:

- the file path
- the file SHA256 checksum
- the size of the file in bytes
- the S3 versionId of the corresponding content object

The information are used for example to determine if a file needs to be backed up again or not (by comparing the SHA256
value).


