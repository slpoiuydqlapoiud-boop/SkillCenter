package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.operations.LangfuseTraceProviderAdapter;
import com.huawei.skillcenter.operations.TraceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects contract adapters only when explicitly configured; Mock remains the default. */
@Configuration
public class ProviderAdapterConfiguration {
    @Bean
    ProviderHttpTransport providerHttpTransport() {
        return new JavaHttpProviderTransport();
    }

    @Bean
    ProviderCredentialResolver providerCredentialResolver() {
        return new EnvironmentProviderCredentialResolver();
    }

    @Bean
    @ConditionalOnProperty(name = "skill-center.providers.runner", havingValue = "openclaw")
    SkillRunner openClawRunner(
            @Value("${skill-center.providers.openclaw.endpoint:}") String endpoint,
            @Value("${skill-center.providers.openclaw.credential-ref:}") String credentialRef,
            @Value("${skill-center.providers.openclaw.mode:contract}") String mode,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new OpenClawRunnerAdapter(new ProviderAdapterConfig(true, endpoint, credentialRef, mode),
                transport, credentials, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "skill-center.providers.evaluation", havingValue = "deepeval")
    EvaluationProvider deepEvalEvaluation(
            @Value("${skill-center.providers.deepeval.endpoint:}") String endpoint,
            @Value("${skill-center.providers.deepeval.credential-ref:}") String credentialRef,
            @Value("${skill-center.providers.deepeval.mode:contract}") String mode,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new DeepEvalEvaluationAdapter(new ProviderAdapterConfig(true, endpoint, credentialRef, mode),
                transport, credentials, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "skill-center.providers.observability", havingValue = "langfuse")
    ObservabilityProvider langfuseObservability(
            @Value("${skill-center.providers.langfuse.endpoint:}") String endpoint,
            @Value("${skill-center.providers.langfuse.credential-ref:}") String credentialRef,
            @Value("${skill-center.providers.langfuse.mode:contract}") String mode,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new LangfuseObservabilityAdapter(new ProviderAdapterConfig(true, endpoint, credentialRef, mode),
                transport, credentials, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "skill-center.providers.trace", havingValue = "langfuse")
    TraceProvider langfuseTraceProvider(
            @Value("${skill-center.providers.langfuse.trace-endpoint:}") String endpoint,
            @Value("${skill-center.providers.langfuse.credential-ref:}") String credentialRef,
            @Value("${skill-center.providers.langfuse.mode:contract}") String mode,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new LangfuseTraceProviderAdapter(new ProviderAdapterConfig(true, endpoint, credentialRef, mode),
                transport, credentials, mapper);
    }
}
