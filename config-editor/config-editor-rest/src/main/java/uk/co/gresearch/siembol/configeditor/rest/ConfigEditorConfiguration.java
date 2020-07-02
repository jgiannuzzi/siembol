package uk.co.gresearch.siembol.configeditor.rest;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uk.co.gresearch.siembol.common.utils.HttpProvider;
import uk.co.gresearch.siembol.configeditor.common.AuthorisationProvider;
import uk.co.gresearch.siembol.configeditor.common.ConfigEditorUtils;
import uk.co.gresearch.siembol.configeditor.configstore.ConfigStoreImpl;

import uk.co.gresearch.siembol.configeditor.configstore.JsonRuleConfigInfoProvider;
import uk.co.gresearch.siembol.configeditor.service.alerts.AlertingRuleSchemaServiceImpl;
import uk.co.gresearch.siembol.configeditor.service.enrichments.EnrichmentSchemaServiceImpl;
import uk.co.gresearch.siembol.configeditor.service.parserconfig.ParserConfigConfigInfoProvider;
import uk.co.gresearch.siembol.configeditor.service.parserconfig.ParserConfigSchemaServiceImpl;
import uk.co.gresearch.siembol.configeditor.service.parsingapp.ParsingAppConfigInfoProvider;
import uk.co.gresearch.siembol.configeditor.service.parsingapp.ParsingAppConfigSchemaServiceImpl;
import uk.co.gresearch.siembol.configeditor.service.response.ResponseSchemaService;
import uk.co.gresearch.siembol.configeditor.serviceaggregator.ServiceAggregator;
import uk.co.gresearch.siembol.configeditor.serviceaggregator.ServiceAggregatorImpl;
import uk.co.gresearch.siembol.configeditor.testcase.TestCaseEvaluator;
import uk.co.gresearch.siembol.configeditor.testcase.TestCaseEvaluatorImpl;

import java.io.IOException;
import java.util.Optional;


@Configuration
@EnableConfigurationProperties(ConfigEditorConfigurationProperties.class)
public class ConfigEditorConfiguration implements DisposableBean {
    private static final String RESPONSE = "response";
    private static final String ALERT = "alert";
    private static final String PARSER_CONFIG = "parserconfig";
    private static final String CORRELATION_ALERT = "correlationalert";
    private static final String PARSING_APP = "parsingapp";
    private static final String ENRICHMENT = "enrichment";
    private static final String UNKNOWN_UI_CONFIG = "Unknown UI config for the service: %s";
    private static final String UNKNOWN_TEST_SPEC_UI_CONFIG = "Unknown Test spec UI config for the service: %s";

    @Autowired
    private ConfigEditorConfigurationProperties properties;
    @Autowired
    private AuthorisationProvider authProvider;

    private ServiceAggregator serviceAggregator;

    private TestCaseEvaluator testCaseEvaluator;

    @Bean(name = "serviceAggregator")
    ServiceAggregator serviceAggregator() throws Exception {
       serviceAggregator = new ServiceAggregatorImpl.Builder(authProvider)
                .addService(RESPONSE,
                        ConfigStoreImpl.createRuleStore(
                                properties.getConfigStore().get(RESPONSE),
                                JsonRuleConfigInfoProvider.create()),
                        new ResponseSchemaService.Builder(
                                new HttpProvider(properties.getCentrifugeUrl(), HttpProvider::getKerberosHttpClient))
                                .uiConfigSchema(readUiConfigFile(RESPONSE).orElse(null))
                                .build())
                .addService(ALERT,
                        ConfigStoreImpl.createRuleStore(
                                properties.getConfigStore().get(ALERT),
                                JsonRuleConfigInfoProvider.create()),
                        AlertingRuleSchemaServiceImpl.createAlertingRuleSchema(
                                readUiConfigFile(ALERT),
                                readTestSpecUiConfigFile(ALERT)))
               .addService(CORRELATION_ALERT,
                       ConfigStoreImpl.createRuleStore(
                               properties.getConfigStore().get(CORRELATION_ALERT),
                               JsonRuleConfigInfoProvider.create()),
                       AlertingRuleSchemaServiceImpl.createAlertingCorrelationRuleSchema(
                               readUiConfigFile(CORRELATION_ALERT)))
               .addService(PARSER_CONFIG,
                        ConfigStoreImpl.createRuleStore(
                                properties.getConfigStore().get(PARSER_CONFIG),
                                ParserConfigConfigInfoProvider.create()),
                        ParserConfigSchemaServiceImpl.createParserConfigSchemaServiceImpl(
                                readUiConfigFile(PARSER_CONFIG),
                                readTestSpecUiConfigFile(PARSER_CONFIG)))
               .addService(PARSING_APP,
                       ConfigStoreImpl.createRuleStore(
                               properties.getConfigStore().get(PARSING_APP),
                               ParsingAppConfigInfoProvider.create()),
                       ParsingAppConfigSchemaServiceImpl.createParserConfigSchemaServiceImpl(
                               readUiConfigFile(PARSING_APP)))
               .addService(ENRICHMENT,
                       ConfigStoreImpl.createRuleStore(
                               properties.getConfigStore().get(ENRICHMENT),
                               JsonRuleConfigInfoProvider.create()),
                       EnrichmentSchemaServiceImpl.createEnrichmentsSchemaService(
                               readUiConfigFile(ENRICHMENT),
                               readTestSpecUiConfigFile(ENRICHMENT)))
               .build();

       return serviceAggregator;
    }

    @Bean(name = "testCaseEvaluator")
    TestCaseEvaluator testCaseEvaluator() throws Exception {
        return new TestCaseEvaluatorImpl();
    }

    @Override
    public void destroy()  {
        if (serviceAggregator != null) {
            serviceAggregator.getConfigStoreServices().forEach(x -> x.shutDown());
        }

        if (serviceAggregator != null) {
            serviceAggregator.getConfigStoreServices().forEach(x -> x.awaitShutDown());
        }
    }

    private Optional<String> readUiConfigFile(String serviceName) {
        String filePath = properties.getUiConfigFileName().get(serviceName);
        if (filePath == null) {
            throw new IllegalArgumentException(String.format(UNKNOWN_UI_CONFIG, serviceName));
        }

        return ConfigEditorUtils.readUiLayoutFile(filePath);
    }

    private Optional<String> readTestSpecUiConfigFile(String serviceName) {
        String filePath = properties.getTestSpecUiConfigFileName().get(serviceName);
        if (filePath == null) {
            throw new IllegalArgumentException(String.format(UNKNOWN_TEST_SPEC_UI_CONFIG, serviceName));
        }

        return ConfigEditorUtils.readUiLayoutFile(filePath);
    }
}