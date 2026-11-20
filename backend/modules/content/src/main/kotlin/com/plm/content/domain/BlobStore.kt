package com.plm.content.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = LoggerFactory.getLogger(BlobStore::class.java)

/** Where file bytes live. Keys are opaque; blobs are written once and never changed. */
internal interface BlobStore {
    /** Stores [file] (a local temporary file) under [key]. */
    fun put(
        key: String,
        file: Path,
        contentType: String,
    )

    fun open(key: String): InputStream

    fun delete(key: String)
}

/** On the local disk: for development and tests, or a single server with a shared volume. */
internal class FileBlobStore(
    private val root: Path,
) : BlobStore {
    init {
        Files.createDirectories(root)
    }

    private fun path(key: String): Path {
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "Bad blob key" }
        return p
    }

    override fun put(
        key: String,
        file: Path,
        contentType: String,
    ) {
        val target = path(key)
        Files.createDirectories(target.parent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun open(key: String): InputStream = Files.newInputStream(path(key))

    override fun delete(key: String) {
        Files.deleteIfExists(path(key))
    }
}

/** An S3 bucket (AWS, or a compatible store such as SeaweedFS or MinIO). */
internal class S3BlobStore(
    private val s3: S3Client,
    private val bucket: String,
) : BlobStore {
    init {
        try {
            s3.headBucket { it.bucket(bucket) }
        } catch (e: Exception) {
            log.info("Creating vault bucket {}", bucket)
            runCatching {
                s3.createBucket { it.bucket(bucket) }
            }.onFailure { log.warn("Couldn't create bucket {}: {}", bucket, it.toString()) }
        }
    }

    override fun put(
        key: String,
        file: Path,
        contentType: String,
    ) {
        s3.putObject({ it.bucket(bucket).key(key).contentType(contentType) }, RequestBody.fromFile(file))
    }

    override fun open(key: String): InputStream =
        try {
            s3.getObject { it.bucket(bucket).key(key) }
        } catch (e: NoSuchKeyException) {
            throw java.io.FileNotFoundException(key)
        }

    override fun delete(key: String) {
        s3.deleteObject { it.bucket(bucket).key(key) }
    }
}

@Configuration(proxyBeanMethods = false)
internal class BlobStoreConfiguration {
    /**
     * `plm.vault.store`: `file` (default, under `plm.vault.dir`) or `s3` (`plm.vault.s3.*`:
     * endpoint, region, bucket, access-key, secret-key).
     */
    @Bean
    fun blobStore(
        @Value("\${plm.vault.store:file}") store: String,
        @Value("\${plm.vault.dir:\${java.io.tmpdir}/plm-vault}") dir: String,
        @Value("\${plm.vault.s3.endpoint:}") endpoint: String,
        @Value("\${plm.vault.s3.region:us-east-1}") region: String,
        @Value("\${plm.vault.s3.bucket:plm-vault}") bucket: String,
        @Value("\${plm.vault.s3.access-key:}") accessKey: String,
        @Value("\${plm.vault.s3.secret-key:}") secretKey: String,
    ): BlobStore =
        when (store) {
            "s3" -> {
                val builder =
                    S3Client
                        .builder()
                        .region(Region.of(region))
                        .httpClient(UrlConnectionHttpClient.create())
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                if (endpoint.isNotBlank()) builder.endpointOverride(URI(endpoint))
                if (accessKey.isNotBlank()) {
                    builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
                    )
                }
                S3BlobStore(builder.build(), bucket)
            }
            else -> FileBlobStore(Path.of(dir).toAbsolutePath().normalize())
        }
}
