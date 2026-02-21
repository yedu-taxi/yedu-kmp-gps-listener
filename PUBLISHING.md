# Publishing to Maven Central - Step by Step Guide

This guide walks you through publishing the **Yedu KMP GPS Listener** library to Maven Central.

## Prerequisites

- JDK 21 installed
- GPG installed (`brew install gnupg` on macOS)
- A [Sonatype/Maven Central](https://central.sonatype.com/) account
- A GitHub repository

---

## Step 1: Create a Sonatype (Maven Central) Account

1. Go to [https://central.sonatype.com/](https://central.sonatype.com/)
2. Click **Sign In** and create an account (you can sign in with GitHub)
3. After signing in, navigate to your **Account** settings

---

## Step 2: Verify Your Namespace (Group ID)

Your current group ID is `io.github.kotlin`. You **must** own this namespace.

### For `io.github.<username>` namespaces:

1. Go to [https://central.sonatype.com/publishing/namespaces](https://central.sonatype.com/publishing/namespaces)
2. Click **Add Namespace**
3. Enter your namespace: `io.github.<your-github-username>` (e.g., `io.github.saggeldi`)
4. Sonatype will ask you to create a **verification repository** on GitHub to prove ownership
5. Create the required repository, then click **Verify**

### Update group ID in your project

Open `library/build.gradle.kts` and update the group to match your verified namespace:

```kotlin
group = "io.github.saggeldi"   // <-- your verified namespace
version = "1.0.0"
```

Also update the `coordinates` block:

```kotlin
mavenPublishing {
    coordinates("io.github.saggeldi", "yedu-kmp-gps-listener", version.toString())
    // ...
}
```

---

## Step 3: Generate a GPG Signing Key

Maven Central requires all artifacts to be signed with GPG.

### 3.1 Generate a new GPG key

```bash
gpg --full-generate-key
```

- Select **RSA and RSA**
- Key size: **4096**
- Expiration: choose your preference (0 = no expiry)
- Enter your **name** and **email**
- Set a **passphrase** (save this - you'll need it later)

### 3.2 List your keys

```bash
gpg --list-keys --keyid-format short
```

Output example:

```
pub   rsa4096/ABCD1234 2026-02-21 [SC]
      1234567890ABCDEF1234567890ABCDEF12345678
uid         [ultimate] Shageldi Alyyew <your@email.com>
```

- **Key ID** = `ABCD1234` (the short ID after `rsa4096/`)
- **Full fingerprint** = the long hex string on the second line

### 3.3 Publish your public key to a keyserver

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234
```

### 3.4 Export the private key (Base64)

```bash
gpg --export-secret-keys ABCD1234 | base64
```

Copy the entire output - this is your `GPG_KEY_CONTENTS` secret.

---

## Step 4: Generate Maven Central Token

1. Go to [https://central.sonatype.com/account](https://central.sonatype.com/account)
2. Click **Generate User Token**
3. You'll receive a **username** and **password** pair
4. Save these values - you'll need them as secrets

---

## Step 5: Configure Secrets

You can publish either **locally** or via **GitHub Actions**.

### Option A: Local Publishing (gradle.properties)

Add these to your **`~/.gradle/gradle.properties`** (NOT the project one):

```properties
mavenCentralUsername=YOUR_SONATYPE_TOKEN_USERNAME
mavenCentralPassword=YOUR_SONATYPE_TOKEN_PASSWORD
signing.keyId=ABCD1234
signing.password=YOUR_GPG_PASSPHRASE
signing.key=BASE64_ENCODED_GPG_PRIVATE_KEY
```

> **WARNING:** Never commit these credentials to version control.

### Option B: GitHub Actions (Recommended)

1. Go to your GitHub repository **Settings > Secrets and variables > Actions**
2. Add the following **repository secrets**:

| Secret Name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Your Sonatype token username |
| `MAVEN_CENTRAL_PASSWORD` | Your Sonatype token password |
| `SIGNING_KEY_ID` | GPG key short ID (e.g., `ABCD1234`) |
| `SIGNING_PASSWORD` | GPG key passphrase |
| `GPG_KEY_CONTENTS` | Base64-encoded GPG private key |

---

## Step 6: Review Your POM Configuration

Your `library/build.gradle.kts` already has the `mavenPublishing` block configured. Make sure everything is correct:

```kotlin
mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "yedu-kmp-gps-listener", version.toString())

    pom {
        name = "Yedu KMP GPS Listener"
        description = "Kotlin multiplatform background gps listener with offline caching"
        inceptionYear = "2026"
        url = "https://github.com/yedu-taxi/yedu-kmp-gps-listener"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "saggeldi"
                name = "Shageldi Alyyew"
                url = "https://shageldi.dev"
            }
        }

        scm {
            url = "https://github.com/yedu-taxi/yedu-kmp-gps-listener"
            connection = "scm:git:git://github.com/yedu-taxi/yedu-kmp-gps-listener.git"
            developerConnection = "scm:git:ssh://git@github.com/yedu-taxi/yedu-kmp-gps-listener.git"
        }
    }
}
```

> **Important:** Maven Central **requires** `licenses` and `scm` blocks. Your current config is missing them - add them as shown above.

---

## Step 7: Publish

### Option A: Publish Locally (from your machine)

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```

### Option B: Publish via GitHub Actions (Recommended)

1. Push all your changes to the `main` branch
2. Go to your GitHub repository
3. Click **Releases > Create a new release**
4. Set a tag (e.g., `v1.0.0`)
5. Set the release title (e.g., `v1.0.0`)
6. Click **Publish release**

This triggers the `.github/workflows/publish.yml` workflow which automatically runs:

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```

---

## Step 8: Verify Publication

### 8.1 Check the deployment status

1. Go to [https://central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments)
2. You should see your deployment in the list
3. If it's in **"Pending"** status, click on it and then click **"Publish"** to release it
4. Wait for validation (usually takes a few minutes)

### 8.2 Verify the artifact is available

Once published, your library will be available at:

```
https://central.sonatype.com/artifact/io.github.saggeldi/yedu-kmp-gps-listener
```

> **Note:** It can take **15-30 minutes** for artifacts to appear in Maven Central search, and up to **4 hours** to be available for dependency resolution.

---

## Step 9: Use Your Published Library

Once published, users can add your library to their projects:

### Kotlin DSL (build.gradle.kts)

```kotlin
// In settings.gradle.kts, ensure mavenCentral() is in repositories
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// In build.gradle.kts (module level)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.saggeldi:yedu-kmp-gps-listener:1.0.0")
        }
    }
}
```

### Groovy DSL (build.gradle)

```groovy
dependencies {
    implementation 'io.github.saggeldi:yedu-kmp-gps-listener:1.0.0'
}
```

### Version Catalog (libs.versions.toml)

```toml
[versions]
yedu-gps = "1.0.0"

[libraries]
yedu-kmp-gps-listener = { module = "io.github.saggeldi:yedu-kmp-gps-listener", version.ref = "yedu-gps" }
```

---

## Publishing a New Version

1. Update the `version` in `library/build.gradle.kts`:
   ```kotlin
   version = "1.1.0"
   ```
2. Commit and push to `main`
3. Create a new GitHub Release with tag `v1.1.0`
4. The GitHub Action will automatically publish to Maven Central

---

## Troubleshooting

### "403 Forbidden" when publishing

- Your namespace is not verified. Go to [Sonatype Namespaces](https://central.sonatype.com/publishing/namespaces) and verify it.
- Your token may have expired. Regenerate it.

### "Signing failed"

- Ensure your GPG key is correctly exported: `gpg --export-secret-keys KEY_ID | base64`
- Make sure the passphrase is correct
- Check that the key ID matches

### "POM validation failed"

Maven Central requires these POM fields:
- `name`
- `description`
- `url`
- `licenses`
- `developers`
- `scm`

Make sure all are present in the `mavenPublishing` block.

### "Deployment failed validation"

- Ensure all artifacts are signed (`.asc` files must be present)
- Ensure javadoc JAR is included (the vanniktech plugin handles this automatically)
- Ensure sources JAR is included (the vanniktech plugin handles this automatically)

### Artifact not appearing in Maven Central

- Check [deployments page](https://central.sonatype.com/publishing/deployments) for status
- Manual publishing may be required on first deployment
- Allow up to 4 hours for sync to complete

---

## Quick Reference

| Item | Value |
|---|---|
| Group ID | `io.github.saggeldi` |
| Artifact ID | `yedu-kmp-gps-listener` |
| Publish Command | `./gradlew publishToMavenCentral --no-configuration-cache` |
| Sonatype Portal | [central.sonatype.com](https://central.sonatype.com/) |
| Plugin Used | [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) v0.34.0 |
| CI/CD | GitHub Actions (triggers on Release) |