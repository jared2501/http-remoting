/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.remoting.http;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.github.kristofa.brave.LoggingSpanCollector;
import com.github.kristofa.brave.Sampler;
import com.github.kristofa.brave.ServerRequestInterceptor;
import com.github.kristofa.brave.ServerResponseInterceptor;
import com.github.kristofa.brave.ServerTracer;
import com.github.kristofa.brave.ThreadLocalServerClientAndLocalSpanState;
import com.github.kristofa.brave.http.DefaultSpanNameProvider;
import com.github.kristofa.brave.jaxrs2.BraveContainerRequestFilter;
import com.github.kristofa.brave.jaxrs2.BraveContainerResponseFilter;
import com.google.common.base.Optional;
import com.palantir.remoting.ssl.SslConfiguration;
import com.palantir.remoting.ssl.SslSocketFactories;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit.DropwizardAppRule;
import java.nio.file.Paths;
import java.util.Random;
import javax.net.ssl.SSLSocketFactory;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.junit.ClassRule;
import org.junit.Test;

public final class FeignClientFactoryTest {
    @ClassRule
    public static final DropwizardAppRule<Configuration> APP = new DropwizardAppRule<>(TestEchoServer.class,
            "src/test/resources/test-server-ssl.yml");

    @Test
    public void testSslSocketFactory_cannotConnectWhenSocketFactoryIsNotSet() throws Exception {
        String endpointUri = "https://localhost:" + APP.getLocalPort();
        TestEchoService service = FeignClients.standard("test suite user agent")
                .createProxy(Optional.<SSLSocketFactory>absent(), endpointUri, TestEchoService.class);

        try {
            service.echo("foo");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(),
                    containsString("sun.security.validator.ValidatorException: PKIX path building failed:"));
        }
    }

    @Test
    public void testSslSocketFactory_canConnectWhenSocketFactoryIsSet() throws Exception {
        TestEchoService service = createProxy();
        assertThat(service.echo("foo"), is("foo"));
    }

    @Test
    public void testBraveTracing() throws Exception {
        TestEchoService service = createProxy();
        service.echo("foo"); // TODO(rfink) This prints stuff to the log. Verify it actually does so.
    }

    private TestEchoService createProxy() {
        String endpointUri = "https://localhost:" + APP.getLocalPort();
        SslConfiguration sslConfig = SslConfiguration.of(Paths.get("src/test/resources/trustStore.jks"));
        return FeignClients.standard("test suite user agent")
                .createProxy(
                        Optional.of(SslSocketFactories.createSslSocketFactory(sslConfig)),
                        endpointUri,
                        TestEchoService.class);
    }


    public static final class TestEchoServer extends Application<Configuration> {
        @Override
        public void run(Configuration config, final Environment env) throws Exception {
            env.jersey().register(new TestEchoResource());
            ServerTracer serverTracer = ServerTracer.builder()
                    .traceSampler(Sampler.ALWAYS_SAMPLE)
                    .randomGenerator(new Random())
                    .state(new ThreadLocalServerClientAndLocalSpanState(0, 1, "server"))
                    .spanCollector(new LoggingSpanCollector())
                    .build();
            env.jersey().register(new BraveContainerRequestFilter(
                    new ServerRequestInterceptor(serverTracer),
                    new DefaultSpanNameProvider()
            ));
            env.jersey().register(new BraveContainerResponseFilter(
                    new ServerResponseInterceptor(serverTracer)
            ));
        }

        public static final class TestEchoResource implements TestEchoService {
            @Override
            public String echo(String value) {
                return value;
            }
        }
    }

    @Path("/")
    public interface TestEchoService {
        @GET
        @Path("/echo")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        String echo(@QueryParam("value") String value);
    }
}
