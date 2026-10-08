package com.ai.agent.starter.config;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.hibernate.validator.resourceloading.PlatformResourceBundleLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Bean Validation 国际化配置。
 *               默认 Hibernate Validator 用 JVM Locale 解析 ValidationMessages，
 *               此处替换为感知 Spring LocaleContextHolder 的 MessageInterpolator，
 *               使校验错误信息跟随请求 Accept-Language 动态切换。
 * @author HUANGcong
 * @since 2026-05-29
 */
@Configuration
public class ValidationConfig {

    @Bean
    public LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean bean = new LocalValidatorFactoryBean();
        bean.setMessageInterpolator(new LocaleAwareMessageInterpolator(
                new ResourceBundleMessageInterpolator(
                        new PlatformResourceBundleLocator("i18n/validationMessages"))));
        return bean;
    }

    private record LocaleAwareMessageInterpolator(MessageInterpolator delegate)
            implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, LocaleContextHolder.getLocale());
        }

        @Override
        public String interpolate(String messageTemplate, Context context, java.util.Locale locale) {
            return delegate.interpolate(messageTemplate, context, locale);
        }
    }
}

