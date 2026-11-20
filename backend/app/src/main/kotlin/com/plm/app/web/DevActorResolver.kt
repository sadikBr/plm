package com.plm.app.web

import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * TEMPORARY, development only: resolves the calling [Actor] from the `X-PLM-User` header.
 *
 * There is no authentication yet. Roadmap step 0.8 replaces this with the Keycloak (OIDC) token
 * subject. Never deploy this anywhere reachable by untrusted users.
 */
internal class DevActorResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == Actor::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Actor {
        val user = webRequest.getHeader(USER_HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_USER
        return Actor(TenantId.DEFAULT, PrincipalId(user))
    }

    companion object {
        const val USER_HEADER = "X-PLM-User"
        const val DEFAULT_USER = "alice"
    }
}

@Configuration(proxyBeanMethods = false)
internal class WebConfiguration : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(DevActorResolver())
    }
}
