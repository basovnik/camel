/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.infinispan;

import java.io.IOException;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.JndiRegistry;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.commons.util.Util;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.sampledomain.User;
import org.infinispan.protostream.sampledomain.marshallers.GenderMarshaller;
import org.infinispan.protostream.sampledomain.marshallers.UserMarshaller;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.remote.client.MarshallerRegistration;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.junit.Test;

import static org.apache.camel.component.infinispan.InfinispanConstants.KEY;
import static org.apache.camel.component.infinispan.InfinispanConstants.OPERATION;
import static org.apache.camel.component.infinispan.InfinispanConstants.QUERY;
import static org.apache.camel.component.infinispan.InfinispanConstants.QUERY_BUILDER;
import static org.apache.camel.component.infinispan.InfinispanConstants.RESULT;
import static org.apache.camel.component.infinispan.InfinispanConstants.VALUE;
import static org.apache.camel.component.infinispan.UserUtils.USERS;
import static org.apache.camel.component.infinispan.UserUtils.createKey;
import static org.apache.camel.component.infinispan.UserUtils.hasUser;

public class InfinispanRemoteQueryProducerIT extends CamelTestSupport {

    private RemoteCacheManager manager;

    @Override
    protected JndiRegistry createRegistry() throws Exception {
        JndiRegistry registry = super.createRegistry();
        registry.bind("myCustomContainer", manager);
        return registry;
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:start").to(
                        "infinispan://?cacheContainer=#myCustomContainer&cacheName=remote_query");
            }
        };
    }

    @Override
    protected void doPreSetup() throws IOException {
        ConfigurationBuilder builder = new ConfigurationBuilder().addServer()
                .host("localhost").port(11222)
                .marshaller(new ProtoStreamMarshaller());

        manager = new RemoteCacheManager(builder.build());

        RemoteCache<String, String> metadataCache = manager
                .getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);
        metadataCache
                .put("sample_bank_account/bank.proto",
                        Util.read(InfinispanRemoteQueryProducerIT.class
                                .getResourceAsStream("/sample_bank_account/bank.proto")));
        MarshallerRegistration.registerMarshallers(ProtoStreamMarshaller
                .getSerializationContext(manager));

        SerializationContext serCtx = ProtoStreamMarshaller
                .getSerializationContext(manager);
        serCtx.registerProtoFiles(FileDescriptorSource
                .fromResources("/sample_bank_account/bank.proto"));
        serCtx.registerMarshaller(new UserMarshaller());
        serCtx.registerMarshaller(new GenderMarshaller());
    }

    @Override
    protected void doPostSetup() throws Exception {
        /* Preload data. */
        for (final User user : USERS) {
            Exchange request = template.request("direct:start",
                    new Processor() {
                        @Override
                        public void process(Exchange exchange) throws Exception {
                            Message in = exchange.getIn();
                            in.setHeader(KEY, createKey(user));
                            in.setHeader(VALUE, user);
                        }
                    });
            assertNull(request.getException());
        }
    }

    @Test
    public void producerQueryOperationWithoutQueryBuilder() throws Exception {
        Exchange request = template.request("direct:start", new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setHeader(OPERATION, QUERY);
            }
        });
        assertNull(request.getException());

        @SuppressWarnings("unchecked")
        List<User> queryResult = (List<User>) request.getIn().getHeader(RESULT);
        assertNull(queryResult);
    }

    @Test
    public void producerQueryWithoutResult() throws Exception {
        Exchange request = template.request("direct:start", new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                exchange.getIn().setHeader(OPERATION, QUERY);
                exchange.getIn().setHeader(QUERY_BUILDER,
                        new InfinispanQueryBuilder() {
                            public Query build(QueryFactory<Query> queryFactory) {
                                return queryFactory.from(User.class)
                                        .having("name").like("%abc%")
                                        .toBuilder().build();
                            }
                        });
            }
        });
        assertNull(request.getException());

        @SuppressWarnings("unchecked")
        List<User> queryResult = (List<User>) request.getIn().getHeader(RESULT);
        assertNotNull(queryResult);
        assertEquals(0, queryResult.size());
    }

    @Test
    public void producerQueryWithResult() throws Exception {
        Exchange request = template.request("direct:start", new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                Message in = exchange.getIn();
                in.setHeader(OPERATION, QUERY);
                in.setHeader(QUERY_BUILDER, new InfinispanQueryBuilder() {
                    public Query build(QueryFactory<Query> queryFactory) {
                        return queryFactory.from(User.class).having("name")
                                .like("%A").toBuilder().build();
                    }
                });
            }
        });
        assertNull(request.getException());

        @SuppressWarnings("unchecked")
        List<User> queryResult = (List<User>) request.getIn().getHeader(RESULT);
        assertNotNull(queryResult);
        assertEquals(2, queryResult.size());
        assertTrue(hasUser(queryResult, "nameA", "surnameA"));
        assertTrue(hasUser(queryResult, "nameA", "surnameB"));
    }
}
