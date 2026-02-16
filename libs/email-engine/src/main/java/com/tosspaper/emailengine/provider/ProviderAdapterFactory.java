package com.tosspaper.emailengine.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.emailengine.provider.impl.MailGunAdapterImpl;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

@Component
public class ProviderAdapterFactory {

    @Getter
    public enum InboundEmailProvider {
        MAILGUN("mailgun");

        private final String name;
        InboundEmailProvider(String name) {
            this.name = name;
        }

        public static InboundEmailProvider from(String provider) {
            return Arrays.stream(InboundEmailProvider.values())
                    .filter(inboundEmailProvider -> inboundEmailProvider.name.equals(provider))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No email provider found for " + provider));
        }
    }

    private final Map<InboundEmailProvider, ProviderAdapter> adapterMap;

    public ProviderAdapterFactory(ObjectMapper objectMapper) {
        this.adapterMap = Map.of(
                InboundEmailProvider.MAILGUN, new MailGunAdapterImpl(objectMapper)
        );
    }

    /**
     * Get the appropriate provider adapter for the given provider name
     *
     * @param provider the provider name (e.g., "mailgun")
     * @return the corresponding ProviderAdapter
     * @throws IllegalArgumentException if no adapter is found for the provider
     */
    public ProviderAdapter getAdapter(String provider) {
        var emailProvider = InboundEmailProvider.from(provider);
        ProviderAdapter adapter = adapterMap.get(emailProvider);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter found for provider: " + provider);
        }

        return adapter;
    }
}
