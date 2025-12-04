/*
 * Copyright 2025-present MongoDB, Inc.
 * Copyright 2014-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.spring.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code AbstractMongoSessionConverter} implementation using Jackson 3.
 *
 * @since 4.0.0
 */
public class JacksonMongoSessionConverter extends AbstractMongoSessionConverter {

    private static final Log LOG = LogFactory.getLog(JacksonMongoSessionConverter.class);

    private static final String ATTRS_FIELD_NAME = "attrs.";

    private static final String PRINCIPAL_FIELD_NAME = "principal";

    private static final String EXPIRE_AT_FIELD_NAME = "expireAt";

    private final ObjectMapper objectMapper;

    /** Creates a new {@link JacksonMongoSessionConverter} with no additional modules registered. */
    public JacksonMongoSessionConverter() {
        this(Collections.emptyList());
    }

    /**
     * Creates a new {@link JacksonMongoSessionConverter} and registers the provided {@link JacksonModule}s.
     *
     * @param modules iterable of modules to register
     */
    public JacksonMongoSessionConverter(final Iterable<JacksonModule> modules) {
        objectMapper = configureJsonMapper().addModules(modules).build();
    }

    /**
     * Creates a new {@link JacksonMongoSessionConverter} using the provided {@link ObjectMapper}.
     *
     * @param objectMapper the object mapper to use; must not be {@code null}
     */
    public JacksonMongoSessionConverter(final ObjectMapper objectMapper) {
        Assert.notNull(objectMapper, "ObjectMapper can NOT be null!");
        this.objectMapper = objectMapper;
    }

    @Override
    @Nullable protected Query getQueryForIndex(final String indexName, final Object indexValue) {

        if (FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
            return Query.query(Criteria.where(PRINCIPAL_FIELD_NAME).is(indexValue));
        } else {
            return Query.query(Criteria.where(ATTRS_FIELD_NAME + MongoSession.coverDot(indexName))
                    .is(indexValue));
        }
    }

    private JsonMapper.Builder configureJsonMapper() {

        JsonMapper.Builder jsonMapperBuilder = JsonMapper.builder();
        jsonMapperBuilder
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

        // serialize fields instead of properties
        jsonMapperBuilder.changeDefaultVisibility(checker ->
                checker.with(JsonAutoDetect.Visibility.NONE).withFieldVisibility(JsonAutoDetect.Visibility.ANY));

        jsonMapperBuilder.propertyNamingStrategy(new MongoIdNamingStrategy());

        jsonMapperBuilder.addModules(
                SecurityJacksonModules.getModules(getClass().getClassLoader()));
        jsonMapperBuilder.addMixIn(MongoSession.class, MongoSessionMixin.class);
        jsonMapperBuilder.addMixIn(HashMap.class, HashMapMixin.class);

        return jsonMapperBuilder;
    }

    @Override
    protected Document convert(final MongoSession source) {

        try {
            Document dbSession = Document.parse(this.objectMapper.writeValueAsString(source));

            // Override default serialization with proper values.
            dbSession.put(PRINCIPAL_FIELD_NAME, extractPrincipal(source));
            dbSession.put(EXPIRE_AT_FIELD_NAME, source.getExpireAt());
            return dbSession;
        } catch (JacksonException ex) {
            throw new IllegalStateException("Cannot convert MongoExpiringSession", ex);
        }
    }

    @Override
    @Nullable protected MongoSession convert(final Document source) {

        Date expireAt = (Date) source.remove(EXPIRE_AT_FIELD_NAME);
        source.remove("originalSessionId");
        String json = source.toJson(
                JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build());

        try {
            MongoSession mongoSession = this.objectMapper.readValue(json, MongoSession.class);
            mongoSession.setExpireAt(expireAt);
            return mongoSession;
        } catch (JacksonException ex) {
            LOG.error("Error during Mongo Session deserialization", ex);
            return null;
        }
    }

    /** Used to whitelist {@link MongoSession} for {@link SecurityJacksonModules}. */
    @SuppressWarnings("unused")
    private static final class MongoSessionMixin {

        @JsonCreator
        MongoSessionMixin(
                @JsonProperty("_id") final String id,
                @JsonProperty("intervalSeconds") final long maxInactiveIntervalInSeconds) {}
    }

    /** Used to whitelist {@link HashMap} for {@link SecurityJacksonModules}. */
    private static final class HashMapMixin {}

    private static final class MongoIdNamingStrategy extends PropertyNamingStrategies.NamingBase {
        private static final long serialVersionUID = 2L;

        @Override
        public String translate(final String propertyName) {

            return switch (propertyName) {
                case "id" -> "_id";
                case "_id" -> "id";
                default -> propertyName;
            };
        }
    }
}
