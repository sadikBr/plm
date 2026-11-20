package com.plm.app.web

import com.plm.platform.actor.Actor
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.OAuthFlow
import io.swagger.v3.oas.models.security.OAuthFlows
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.tags.Tag
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI contract (roadmap 0.10). Served at `/v3/api-docs`, browsable at `/swagger-ui.html`.
 * The committed copy, `api/openapi.json`, is kept in sync by `OpenApiContractIT`, and the frontend
 * generates its typed client from it.
 */
@Configuration(proxyBeanMethods = false)
internal class OpenApiConfiguration {
    init {
        // Actor is resolved from the bearer token, never sent by clients.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor::class.java)
    }

    @Bean
    fun plmOpenApi(
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuer: String,
    ): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("PLM API")
                    .version("v1")
                    .description(
                        "Product lifecycle management API. Every operation needs an OAuth2 bearer token. " +
                            "Errors are RFC 9457 problem details with a stable `code`.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    SECURITY_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Keycloak. In dev: client `plm-web`, authorization code with PKCE.")
                        .flows(
                            OAuthFlows().authorizationCode(
                                OAuthFlow()
                                    .authorizationUrl("$issuer/protocol/openid-connect/auth")
                                    .tokenUrl("$issuer/protocol/openid-connect/token"),
                            ),
                        ),
                ),
            ).addSecurityItem(SecurityRequirement().addList(SECURITY_SCHEME))

    /** Stable, readable operation ids (`versionedObjectCheckOut`) instead of `get_1`, `list_2`... */
    @Bean
    fun operationIds(): OperationCustomizer =
        OperationCustomizer { operation, handler ->
            val controller = handler.beanType.simpleName.removeSuffix("Controller")
            val method = handler.method.name.replaceFirstChar { it.uppercase() }
            operation.operationId = controller.replaceFirstChar { it.lowercase() } + method
            operation
        }

    /**
     * Applied to the finished document: springdoc assigns tags and builds schemas after the
     * per-operation customizers run, so these fixes must happen here.
     */
    @Bean
    fun contractPolish(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            val components = api.components ?: Components().also { api.components = it }

            // 1. Kotlin DTO fields that are not nullable are always present: mark them required,
            //    so generated clients type them as `id: string`, not `id?: string`.
            components.schemas.orEmpty().values.forEach { schema ->
                val required =
                    schema.properties
                        .orEmpty()
                        .filterValues { !it.isNullable() }
                        .keys
                        .sorted()
                schema.required = required.ifEmpty { null }
            }

            // 2. Every operation can fail with a problem-details body. Document it once, for all.
            components.addSchemas(PROBLEM_SCHEMA, problemSchema())
            val problem =
                ApiResponse()
                    .description("Error (RFC 9457 problem details)")
                    .content(
                        Content().addMediaType(
                            "application/problem+json",
                            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_SCHEMA")),
                        ),
                    )
            api.paths.orEmpty().values.forEach { path ->
                path.readOperations().forEach { op -> op.responses.addApiResponse("default", problem) }
            }

            // 3. Human tag names: `versioned-object-controller` -> `Versioned object`.
            val renamed = mutableMapOf<String, String>()
            api.paths.orEmpty().values.flatMap { it.readOperations() }.forEach { op ->
                op.tags = op.tags?.map { tag -> renamed.getOrPut(tag) { humanTag(tag) } }
            }
            api.tags =
                renamed.values
                    .distinct()
                    .sorted()
                    .map { Tag().name(it) }
        }

    private fun humanTag(tag: String) =
        tag.removeSuffix("-controller").replace('-', ' ').replaceFirstChar {
            it.uppercase()
        }

    /** Nullable, or untyped (`{}` accepts any JSON value, null included, e.g. an attribute's default value). */
    private fun Schema<*>.isNullable(): Boolean =
        nullable == true || types?.contains("null") == true || (types.isNullOrEmpty() && type == null && `$ref` == null)

    private fun problemSchema(): Schema<*> =
        ObjectSchema()
            .description("RFC 9457 problem details")
            .addProperty("type", StringSchema().example("urn:plm:error:ALREADY_CHECKED_OUT"))
            .addProperty("title", StringSchema())
            .addProperty("status", IntegerSchema())
            .addProperty("detail", StringSchema())
            .addProperty("instance", StringSchema())
            .addProperty("code", StringSchema().description("Stable machine-readable error code"))
            .addProperty("details", ObjectSchema().additionalProperties(true))

    private companion object {
        const val SECURITY_SCHEME = "keycloak"
        const val PROBLEM_SCHEMA = "Problem"
    }
}
