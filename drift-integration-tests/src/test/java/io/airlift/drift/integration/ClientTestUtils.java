/*
 * Copyright (C) 2012 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.airlift.drift.integration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.drift.client.DriftClient;
import io.airlift.drift.client.MethodInvocationFilter;
import io.airlift.drift.codec.ThriftCodecManager;
import io.airlift.drift.codec.guice.ThriftCodecModule;
import io.airlift.drift.integration.scribe.apache.LogEntry;
import io.airlift.drift.integration.scribe.drift.DriftLogEntry;
import io.airlift.drift.integration.scribe.drift.DriftResultCode;
import io.airlift.drift.integration.scribe.drift.DriftScribe;
import io.airlift.drift.transport.AddressSelector;
import io.airlift.drift.transport.apache.PemReader;
import io.airlift.drift.transport.netty.DriftNettyClientConfig.Protocol;
import io.airlift.drift.transport.netty.DriftNettyClientConfig.Transport;
import io.airlift.jmx.testing.TestingJmxModule;
import org.weakref.jmx.guice.MBeanModule;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.airlift.drift.client.guice.DriftClientBinder.driftClientBinder;
import static io.airlift.drift.client.guice.MethodInvocationFilterBinder.staticFilterBinder;
import static io.airlift.drift.transport.apache.PemReader.loadTrustStore;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

final class ClientTestUtils
{
    public static final ThriftCodecManager CODEC_MANAGER = new ThriftCodecManager();
    public static final List<LogEntry> MESSAGES = ImmutableList.of(
            new LogEntry("hello", "world"),
            new LogEntry("bye", "world"));
    public static final List<DriftLogEntry> DRIFT_MESSAGES = ImmutableList.copyOf(
            MESSAGES.stream()
                    .map(input -> new DriftLogEntry(input.category, input.message))
                    .collect(Collectors.toList()));
    public static final DriftResultCode DRIFT_OK = DriftResultCode.OK;

    private ClientTestUtils() {}

    public static int logDriftClientBinder(
            HostAndPort address,
            List<DriftLogEntry> entries,
            Module transportModule,
            List<MethodInvocationFilter> filters,
            Transport transport,
            Protocol protocol,
            boolean secure)
    {
        // filters are not supported in the binder yet
        if (!filters.isEmpty()) {
            return 0;
        }

        AddressSelector addressSelector = context -> ImmutableList.of(address);

        Bootstrap app = new Bootstrap(
                new MBeanModule(),
                new TestingJmxModule(),
                new ThriftCodecModule(),
                transportModule,
                binder -> driftClientBinder(binder).bindDriftClient(DriftScribe.class)
                        .withAddressSelector(addressSelector)
                        .withMethodInvocationFilter(staticFilterBinder(filters)),
                binder -> driftClientBinder(binder).bindDriftClient(DriftScribe.class, CustomClient.class)
                        .withAddressSelector(addressSelector)
                        .withMethodInvocationFilter(staticFilterBinder(filters)),
                binder -> binder.bind(ScribeUser.class).in(Scopes.SINGLETON));

        LifeCycleManager lifeCycleManager = null;

        try {
            app.setRequiredConfigurationProperties(ImmutableMap.<String, String>builder()
                    .put("scribe.thrift.client.transport", transport.toString())
                    .put("scribe.CustomClient.thrift.client.transport", transport.toString())
                    .put("scribe.thrift.client.protocol", protocol.toString())
                    .put("scribe.CustomClient.thrift.client.protocol", protocol.toString())
                    .build());

            // Nifty ssl configuration is for all clients, where Apache and Netty are configured per client
            app.setOptionalConfigurationProperties(ImmutableMap.<String, String>builder()
                    .put("thrift.client.ssl.enabled", String.valueOf(secure))
                    .put("scribe.thrift.client.ssl.enabled", String.valueOf(secure))
                    .put("scribe.CustomClient.thrift.client.ssl.enabled", String.valueOf(secure))
                    .put("thrift.client.ssl.trust-certificate", getCertificateChainFile().getAbsolutePath())
                    .put("scribe.thrift.client.ssl.trust-certificate", getCertificateChainFile().getAbsolutePath())
                    .put("scribe.CustomClient.thrift.client.ssl.trust-certificate", getCertificateChainFile().getAbsolutePath())
                    .build());

            Injector injector = app
                    .strictConfig()
                    .doNotInitializeLogging()
                    .initialize();

            lifeCycleManager = injector.getInstance(LifeCycleManager.class);
            DriftScribe scribe = injector.getInstance(DriftScribe.class);

            ScribeUser user = injector.getInstance(ScribeUser.class);

            assertEquals(scribe.log(entries), DRIFT_OK);

            assertEquals(user.getClient().log(entries), DRIFT_OK);
            assertEquals(user.getClientCustom().log(entries), DRIFT_OK);
            assertEquals(user.getFactory().get().log(entries), DRIFT_OK);
            assertEquals(user.getFactoryCustom().get().log(entries), DRIFT_OK);

            assertSame(scribe, user.getClient());
            assertNotSame(user.getClient(), user.getClientCustom());
            assertNotSame(user.getFactory(), user.getFactoryCustom());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            if (lifeCycleManager != null) {
                try {
                    lifeCycleManager.stop();
                }
                catch (Exception ignored) {
                }
            }
        }
        return 5;
    }

    public static SSLContext getServerSslContext()
            throws IOException, GeneralSecurityException
    {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(loadTrustStore(getCertificateChainFile()));

        String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
        keyManagerFactory.init(PemReader.loadKeyStore(getCertificateChainFile(), getPrivateKeyFile(), Optional.empty()), new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    public static SSLContext getClientSslContext()
            throws IOException, GeneralSecurityException
    {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(loadTrustStore(getCertificateChainFile()));

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static File getPrivateKeyFile()
    {
        return getResourceFile("rsa.key");
    }

    public static File getCertificateChainFile()
    {
        return getResourceFile("rsa.crt");
    }

    private static File getResourceFile(String name)
    {
        URL resource = TestClientsWithApacheServer.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalArgumentException("Resource not found " + name);
        }
        return new File(resource.getFile());
    }

    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @Qualifier
    private @interface CustomClient {}

    private static class ScribeUser
    {
        @Inject
        private DriftScribe client;

        @Inject
        @CustomClient
        private DriftScribe clientCustom;

        @Inject
        private DriftClient<DriftScribe> factory;

        @Inject
        @CustomClient
        private DriftClient<DriftScribe> factoryCustom;

        public DriftScribe getClient()
        {
            return client;
        }

        public DriftScribe getClientCustom()
        {
            return clientCustom;
        }

        public DriftClient<DriftScribe> getFactory()
        {
            return factory;
        }

        public DriftClient<DriftScribe> getFactoryCustom()
        {
            return factoryCustom;
        }
    }
}
