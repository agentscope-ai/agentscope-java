# Maven Central release

[中文](MAVEN_RELEASE_zh.md)

The `Maven Central staging` workflow (`maven-central-release.yml`) runs on a pushed
version tag, such as `v2.1.0-BETA2`, `v2.1.0-RC1`, or `v2.1.0`.
SNAPSHOT versions and Service/Go module tags are not release inputs.
It does not run on branch pushes or pull requests.

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored private signing key |
| `MAVEN_GPG_PASSPHRASE` | Signing key passphrase |

Before tagging, set `revision` to the release version in the root `pom.xml`,
`agentscope-dependencies-bom/pom.xml`, and
`agentscope-distribution/agentscope-bom/pom.xml`. Merge the release preparation
and workflow changes into the intended release branch. The workflow rejects
a tag that differs from any of these versions; it never overrides the version
of a SNAPSHOT checkout.

For example, after preparing `2.1.0-BETA2` on `main`:

```bash
git switch main
git pull --ff-only origin main
git tag -a v2.1.0-BETA2 -m "Release 2.1.0-BETA2"
git push origin v2.1.0-BETA2
```

The workflow checks module/BOM declarations, runs Maven tests and formatting
checks, generates sources and Javadoc, signs artifacts, and uploads the Maven
library reactor (Core, Harness, extensions, BOMs, and distribution). Service
applications, examples, and provider E2E tests are excluded from this reactor.
Their release workflows are independent.

The deploy command explicitly sets `-DautoPublish=false -DwaitUntil=validated`.
Success means the deployment is **VALIDATED**, awaiting manual publication;
it does not make the packages available on Maven Central. Review
`agentscope-java-<version>` in [Central Portal deployments](https://central.sonatype.com/publishing/deployments)
and click **Publish** when ready. See the [Central plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).

For a failed run, inspect the Actions log and Portal state before rerunning.
An upload may have succeeded even if a later validation request timed out.
Remove an unwanted unpublished deployment in the Portal before uploading again;
published versions cannot be overwritten. Rerun the workflow for the same tag
instead of moving the tag.

After creating the release tag, subsequent development commits can return
Maven versions to `SNAPSHOT`; the workflow builds the tagged source.
