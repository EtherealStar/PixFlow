package com.pixflow.app.web.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.pixflow.app.web.auth.AuthController;
import com.pixflow.app.web.conversation.MessageController;
import com.pixflow.app.web.outputs.OutputController;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class StrictHttpJsonConfigurationTest {
    private ObjectMapper applicationMapper;
    private ObjectMapper httpMapper;

    @BeforeEach
    void setUp() {
        applicationMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var converter = new MappingJackson2HttpMessageConverter(applicationMapper);
        var converters = new ArrayList<HttpMessageConverter<?>>();
        converters.add(converter);

        new StrictHttpJsonConfiguration(applicationMapper).extendMessageConverters(converters);
        httpMapper = converter.getObjectMapper();
    }

    @Test
    void httpBoundaryRejectsUnknownFieldsForCanonicalCommands() {
        assertUnknownField(AuthController.LoginCommand.class,
                "{\"username\":\"admin\",\"password\":\"secret\",\"rememberMe\":true}");
        assertUnknownField(MessageController.MessageCommand.class,
                "{\"prompt\":\"hello\",\"references\":[],\"packageId\":7}");
        assertUnknownField(MessageController.MessageCommand.class,
                "{\"prompt\":\"hello\",\"references\":[],\"attachments\":[]}");
        assertUnknownField(OutputController.RenameRequest.class,
                "{\"displayName\":\"result.png\",\"objectKey\":\"secret\"}");
    }

    @Test
    void strictHttpMapperDoesNotChangeApplicationMapper() throws Exception {
        assertThat(httpMapper).isNotSameAs(applicationMapper);
        assertThat(httpMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(applicationMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();

        AuthController.LoginCommand command = applicationMapper.readValue(
                "{\"username\":\"admin\",\"password\":\"secret\",\"rememberMe\":true}",
                AuthController.LoginCommand.class);
        assertThat(command.username()).isEqualTo("admin");
    }

    private void assertUnknownField(Class<?> type, String json) {
        assertThatThrownBy(() -> httpMapper.readValue(json, type))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
