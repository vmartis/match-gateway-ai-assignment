package com.vmartis.matchgateway.gateway.shared.api

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

/**
 * Strict request JSON with Jackson built-ins: string fields accept only JSON strings (`"subjectId": 123` must be 400,
 * not the valid id "123"), number fields reject numeric strings (`"threshold": "0.7"` must be 400; Jackson still binds "NaN"/"Infinity", which bean validation rejects), and duplicate
 * keys are rejected so no intermediary can read a different value than we do (security review step 4).
 */
@Configuration
class StrictJsonConfig {
    @Bean
    fun strictScalars() =
        JsonMapperBuilderCustomizer { builder ->
            builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            builder.withCoercionConfig(LogicalType.Textual) { config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
            builder.withCoercionConfig(LogicalType.Float) { config ->
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            }
        }
}
